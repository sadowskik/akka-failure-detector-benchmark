package io.bernhardt.akka

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, FSM, Props}
import akka.cluster.ClusterEvent._
import akka.cluster._
import akka.http.scaladsl.model.DateTime
import akka.pattern.{AskTimeoutException, ask, pipe}
import akka.util.Timeout
import com.github.tototoshi.csv.CSVWriter
import io.bernhardt.akka.BenchmarkCoordinator._
import io.bernhardt.akka.BenchmarkNode.{BecomeUnreachable, ExpectUnreachable, Reconfigure}
import org.HdrHistogram.Histogram

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Random

/**
  * TODO protobuf messages
  */
class BenchmarkCoordinator extends Actor with FSM[State, Data] with ActorLogging {

  import context.dispatcher

  val cluster = Cluster(context.system)

  val expectedMembers = context.system.settings.config.getInt("benchmark.expected-members")

  val warmupTime = Duration.create(context.system.settings.config.getDuration("benchmark.warmup-time").getSeconds, TimeUnit.SECONDS)

  val removeUnscheduledUnreachableTimeout = Duration.create(context.system.settings.config.getDuration("benchmark.remove-unscheduled-unreachable-timeout").getSeconds, TimeUnit.SECONDS)

  val rounds = context.system.settings.config.getInt("benchmark.rounds")

  val plan: List[RoundConfiguration] = {
    import scala.collection.JavaConverters._
    context.system.settings.config.getConfigList("benchmark.plan").asScala.map { c =>
      RoundConfiguration(c.getString("fd"), c.getDouble("threshold"))
    }
  }.toList

  val step = Option(context.system.settings.config.getInt("benchmark.step")).getOrElse(0)

  val detectionTiming = new Histogram(30.seconds.toMicros, 3)

  val detectionTimingCsvFile = {
    val d = System.getProperty("java.io.tmpdir")
    val f = new File(d, "fd-benchmark.csv")
    if (!f.exists()) {
        f.createNewFile()
    }
    f
  }

  var lastGoodMembers: SortedSet[Member] = SortedSet.empty

  override def preStart() = {
    super.preStart()
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[MemberUp], classOf[MemberRemoved], classOf[UnreachableMember], classOf[ReachableMember])
    log.info("Benchmark coordinator started")
  }

  override def postStop() = {
    super.postStop()
    cluster.unsubscribe(self)
  }

  startWith(WaitingForMembers, WaitingData(1, warmedUp = false))

  when(WaitingForMembers, warmupTime) {
    case Event(StateTimeout, data: WaitingData) =>
      proceedIfReady(data, warmedUp = true)
    case Event(MemberUp(_), data: WaitingData) =>
      proceedIfReady(data, warmedUp = data.warmedUp)
    case Event(UnreachableMember(member), _) =>
      setTimer(member.uniqueAddress.toString, RemoveUnscheduledUnreachable(member), removeUnscheduledUnreachableTimeout)
      stay
    case Event(ReachableMember(member), _) =>
      cancelTimer(member.uniqueAddress.toString)
      stay
    case Event(RemoveUnscheduledUnreachable(member), data: Data) =>
      removeFalsePositive(member, data.round)
    case Event(MemberRemoved(member, _), data: WaitingData) =>
      log.warning(s"Member ${member.address} removed")
      stay
  }

  when(PreparingBenchmark, 1.minute) {
    case Event(StateTimeout, data: BenchmarkData) =>
      log.error("Stuck in benchmark preparation, going back to WaitingForMembers")
      goto(WaitingForMembers) using WaitingData(round = data.round, warmedUp = true)
    case Event(PrepareBenchmark, data: BenchmarkData) =>
      sendMessageToAll(data.members, ExpectUnreachable(data.target))
      stay
    case Event(ExpectUnreachableAck(address), data: BenchmarkData) =>
      val acked = data.ackedExpectUnreachable + address
      log.debug("{}/{} members acked benchmark start", acked.size, expectedMembers)
      if (acked.size == expectedMembers) {
        setTimer("startBenchmark", StartBenchmark, 1.second)
        goto(Benchmarking) using data.copy(ackedExpectUnreachable = acked)
      } else {
        stay using data.copy(ackedExpectUnreachable = acked)
      }
    case Event(MessageDeliveryTimeout(address, message), data) =>
      log.info("Redelivering message {} to {}", message, address)
      sendMessage(address, message)
      stay using data
    case Event(UnreachableMember(member), _) =>
      setTimer(member.uniqueAddress.toString, RemoveUnscheduledUnreachable(member), removeUnscheduledUnreachableTimeout)
      stay
    case Event(ReachableMember(member), _) =>
      cancelTimer(member.uniqueAddress.toString)
      stay
    case Event(RemoveUnscheduledUnreachable(member), data: Data) =>
      removeFalsePositive(member, data.round)
    case Event(MemberUp(member), data: BenchmarkData) =>
      log.warning(s"Member ${member.address} added during preparation")
      stay
    case Event(MemberRemoved(member, _), data: BenchmarkData) =>
      log.warning(s"Member ${member.address} removed during preparation")
      goto(WaitingForMembers) using WaitingData(round = data.round, warmedUp = true)
  }

  when(Benchmarking, 1.minute) {
    case Event(StateTimeout, data: BenchmarkData) =>
      log.error("Stuck in benchmark preparation, going back to WaitingForMembers")
      goto(WaitingForMembers) using WaitingData(round = data.round, warmedUp = true)
    case Event(StartBenchmark, data: BenchmarkData) =>
      // we want the members only to start their timer now so instead of sending the shutdown instruction only to one member we send it to all
      data.members.foreach { m =>
        context.actorSelection(BenchmarkNode.path(m.address)) ! BecomeUnreachable(data.target.uniqueAddress)
      }
      stay
    case Event(MemberUnreachabilityDetected(member, duration), data: BenchmarkData) =>
      val durations = data.detectionDurations :+ duration
      if (durations.size == expectedMembers - 1) {
        onRoundFinished(data)
      } else {
        stay using data.copy(detectionDurations = durations)
      }
    case Event(UnreachableMember(member), data: BenchmarkData) if member.uniqueAddress == data.target.uniqueAddress =>
      cluster.down(member.address)
      stay using data.copy(members = data.members - member)
    case Event(UnreachableMember(member), _) =>
      setTimer(member.uniqueAddress.toString, RemoveUnscheduledUnreachable(member), removeUnscheduledUnreachableTimeout)
      stay
    case Event(ReachableMember(member), _) =>
      cancelTimer(member.uniqueAddress.toString)
      stay
    case Event(RemoveUnscheduledUnreachable(member), data: Data) =>
      removeFalsePositive(member, data.round)
    case Event(MemberUp(member), _) =>
      log.warning(s"Member ${member.address} added during run")
      stay
    case Event(MemberRemoved(member, _), data: BenchmarkData) if member.uniqueAddress != data.target.uniqueAddress =>
      log.warning(s"Member ${member.address} removed during run, aborting")
      goto(WaitingForMembers) using WaitingData(round = data.round, warmedUp = true)
  }

  when(Done) {
    case Event(any, _) =>
      log.info(any.toString)
      stay
  }

  onTransition { case from -> to =>
    log.info("Transitioning from {} to {}", from, to)
  }


  private def proceedIfReady(data: WaitingData, warmedUp: Boolean) = {
    val members = availableClusterMembers
    if (data.configureStep.isDefined) {
      val nextStep = data.configureStep.get
      if (members.size >= expectedMembers) {
        sendMessageToAll(members, Reconfigure(plan(nextStep).implementationClass, plan(nextStep).threshold, nextStep))
        goto(Done)
      } else {
        stay
      }
    } else {
      if (members.size >= expectedMembers && warmedUp) {
        log.info(s"${members.size}/$expectedMembers required members joined, preparing benchmark")
        val availableMembers = roundMembers
        lastGoodMembers = availableMembers
        prepareBenchmark(availableMembers, data.round)
      } else {
        log.info(s"Not starting yet as we only have ${members.size}/$expectedMembers members and warmup is $warmedUp")
        if (expectedMembers - members.size < 4 && lastGoodMembers.nonEmpty) {
          log.info("Missing members {}", lastGoodMembers.diff(members).map(_.uniqueAddress).mkString(" "))
        }
        stay using data.copy(warmedUp = warmedUp)
      }
    }
  }

  private def prepareBenchmark(members: SortedSet[Member], round: Int) = {
    val candidates = members.filterNot(_.uniqueAddress == cluster.selfUniqueAddress)
    val target = candidates.toList(Random.nextInt(candidates.size))

    log.info(
      s"""
         |*********************
         |Starting benchmarking round $round/$rounds (step ${step + 1}/${plan.size}) with ${members.size}/$expectedMembers member nodes, making ${target.address} unreachable
         |*********************""".stripMargin)
    setTimer("preparBenchmark", PrepareBenchmark, 1.second)
    goto(PreparingBenchmark) using BenchmarkData(round = round, target = target, members = members)
  }

  private def onRoundFinished(data: BenchmarkData) = {
    log.info("Round {} done".stripMargin, data.round)

    val executionPlan = plan(step)
    val csv = CSVWriter.open(detectionTimingCsvFile, append = true)

    data.detectionDurations.foreach { durationNanos =>
      detectionTiming.recordValue(durationNanos.nanos.toMicros)
      csv.writeRow(List(executionPlan.implementationClass, executionPlan.threshold, durationNanos.nanos.toMicros))
    }

    csv.close()

    if (data.round == rounds) {
      reportRoundResults()
      configureStep(availableClusterMembers)
    } else {
      if (data.members.size < expectedMembers) {
        log.info("Waiting for enough members to join")
        goto(WaitingForMembers) using WaitingData(data.round + 1, warmedUp = true)
      } else {
        prepareBenchmark(data.members, data.round + 1)
      }
    }
  }

  private def sendMessageToAll(members: Set[Member], message: Any): Unit = {
    members.foreach { m =>
      sendMessage(m.uniqueAddress, message)
    }
  }

  private def sendMessage(to: UniqueAddress, message: Any): Unit = {
    implicit val timeout = Timeout(3.seconds)
    val f = context.actorSelection(BenchmarkNode.path(to.address)) ? message
    f.recover { case a: AskTimeoutException =>
      log.warning(s"No answer from $to in $timeout")
      MessageDeliveryTimeout(to, message)
    } pipeTo self
  }

  private def shutdownMember(uniqueAddress: UniqueAddress): Unit = {
    context.actorSelection(BenchmarkNode.path(uniqueAddress.address)) ! BecomeUnreachable(uniqueAddress)
  }

  private def removeFalsePositive(member: Member, round: Int) = {
    log.error(s"************* Member ${member.address} unreachable, probably a false positive from the FD. Getting rid of it")
    shutdownMember(member.uniqueAddress)
    cluster.down(member.address)
    goto(WaitingForMembers) using WaitingData(round = round, warmedUp = true)
  }

  private def reportRoundResults(): Unit = {
    val out = new ByteArrayOutputStream()
    detectionTiming.outputPercentileDistribution({
      new PrintStream(out)
    }, 1.0)
    val histogram = new String(out.toByteArray, StandardCharsets.UTF_8)
    val report =
      s"""
         |*********
         |Benchmark report for ${cluster.settings.FailureDetectorImplementationClass}
         |$expectedMembers nodes
         |$rounds rounds
         |${step + 1} / ${plan.size} steps
         |
         |Threshold: ${cluster.settings.FailureDetectorConfig.getDouble("threshold")}
         |
         |50% percentile: ${detectionTiming.getValueAtPercentile(50)} µs
         |90% percentile: ${detectionTiming.getValueAtPercentile(90)} µs
         |99% percentile: ${detectionTiming.getValueAtPercentile(99)} µs
         |
         |Detection latencies (µs):
         |
         |$histogram
         |*********
        """.stripMargin
    log.info(report)
    val hostname = context.system.settings.config.getString("akka.remote.netty.tcp.hostname")
    val subject = s"Akka FD Benchmark results $hostname ${DateTime.now.toString()}"
    Reporting.email(subject, report, context.system)

    val timingReport = Source.fromFile(detectionTimingCsvFile).getLines().mkString("\n")
    Reporting.email(s"FD Benchmark report ${DateTime.now.toString()}", timingReport, context.system)
  }

  private def configureStep(members: Set[Member]) = {
    val nextStep = step + 1
    if (nextStep == plan.size) {
      log.info("Benchmark done!")
      goto(Done)
    } else {
      goto(WaitingForMembers) using WaitingData(round = 0, warmedUp = false, configureStep = Some(nextStep))
    }
  }


  private def availableClusterMembers: SortedSet[Member] = cluster.state.members.filter(s => s.status == MemberStatus.Up || s.status == MemberStatus.WeaklyUp)

  private def roundMembers: SortedSet[Member] = availableClusterMembers.take(expectedMembers)

}

object BenchmarkCoordinator {
  def props = Props(classOf[BenchmarkCoordinator])

  val name = "benchmark-coordinator"

  sealed trait State

  case object WaitingForMembers extends State

  case object PreparingBenchmark extends State

  case object Benchmarking extends State

  case object Done extends State

  sealed trait Data {
    val round: Int
  }

  case class WaitingData(round: Int, warmedUp: Boolean, configureStep: Option[Int] = None) extends Data

  case class BenchmarkData(round: Int, target: Member, detectionDurations: List[Long] = List.empty, members: SortedSet[Member], start: Long = System.nanoTime(), ackedExpectUnreachable: Set[UniqueAddress] = Set.empty) extends Data

  case class RoundConfiguration(implementationClass: String, threshold: Double)

  // inbound messages

  case object PrepareBenchmark

  case object StartBenchmark

  case class RemoveUnscheduledUnreachable(member: Member)

  case class MemberUnreachabilityDetected(detectedBy: UniqueAddress, duration: Long)

  case class ExpectUnreachableAck(from: UniqueAddress)

  case class ReconfigurationAck(from: UniqueAddress)

  case class MessageDeliveryTimeout(member: UniqueAddress, msg: Any)

}
