package integration.ergodicity.engine

import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{Actor, ActorSystem}
import akka.dispatch.Await
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.ListenerBinding
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config._
import com.ergodicity.core.FutureContract
import com.ergodicity.core.OrderType.ImmediateOrCancel
import com.ergodicity.core.{IsinId, Isin, ShortIsin}
import com.ergodicity.engine.Listener._
import com.ergodicity.engine.ReplicationScheme._
import com.ergodicity.engine.Services.StartServices
import com.ergodicity.engine.service.Trading.Buy
import com.ergodicity.engine.service.Trading.OrderExecution
import com.ergodicity.engine.service.Trading.Sell
import com.ergodicity.engine.service._
import com.ergodicity.engine.underlying._
import com.ergodicity.engine.{ServicesState, ServicesActor, Engine}
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{Connection => CGConnection, P2TypeParser, CGate, Publisher => CGPublisher}
import scala.Left
import scala.Right

class TradingIntegrationSpec extends TestKit(ActorSystem("TradingIntegrationSpec", com.ergodicity.engine.EngineSystemConfig)) with WordSpec with BeforeAndAfterAll {

  val log = Logging(system, "TradingIntegrationSpec")

  val Host = "localhost"
  val Port = 4001

  val ReplicationConnection = Tcp(Host, Port, system.name + "Replication")
  val PublisherConnection = Tcp(Host, Port, system.name + "Publisher")
  val RepliesConnection = Tcp(Host, Port, system.name + "Repl")

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
    P2TypeParser.setCharset("windows-1251")
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  trait Connections extends UnderlyingConnection with UnderlyingTradingConnections {
    val underlyingConnection = new CGConnection(ReplicationConnection())

    val underlyingTradingConnection = new CGConnection(PublisherConnection())
  }

  trait Replication extends FutInfoReplication with OptInfoReplication with PosReplication with FutOrdersReplication with OptOrdersReplication with FutTradesReplication with OptTradesReplication {
    val optInfoReplication = Replication("FORTS_OPTINFO_REPL", new File("cgate/scheme/OptInfo.ini"), "CustReplScheme")

    val futInfoReplication = Replication("FORTS_FUTINFO_REPL", new File("cgate/scheme/FutInfo.ini"), "CustReplScheme")

    val posReplication = Replication("FORTS_POS_REPL", new File("cgate/scheme/Pos.ini"), "CustReplScheme")

    val futOrdersReplication = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutOrders.ini"), "CustReplScheme")

    val optOrdersReplication = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptOrders.ini"), "CustReplScheme")

    val futTradesReplication = Replication("FORTS_FUTTRADE_REPL", new File("cgate/scheme/FutTrades.ini"), "CustReplScheme")

    val optTradesReplication = Replication("FORTS_OPTTRADE_REPL", new File("cgate/scheme/OptTrades.ini"), "CustReplScheme")
  }

  trait Publisher extends UnderlyingPublisher {
    self: Engine with UnderlyingTradingConnections =>
    val publisherName: String = "Engine"
    val brokerCode: String = "533"
    val messagesConfig = FortsMessages(publisherName, 5.seconds, new File("./cgate/scheme/FortsMessages.ini"))
    val underlyingPublisher = new CGPublisher(underlyingTradingConnection, messagesConfig())
  }

  trait Listeners extends FutInfoListener with OptInfoListener with FutTradesListener with OptTradesListener with FutOrdersListener with OptOrdersListener with RepliesListener with PosListener {
    self: Connections with Replication with Publisher =>

    val futInfoListener = ListenerBinding(underlyingConnection, futInfoReplication)
    val optInfoListener = ListenerBinding(underlyingConnection, optInfoReplication)

    val futOrdersListener = ListenerBinding(underlyingConnection, futOrdersReplication)
    val optOrdersListener = ListenerBinding(underlyingConnection, optOrdersReplication)

    val futTradesListener = ListenerBinding(underlyingConnection, futTradesReplication)
    val optTradesListener = ListenerBinding(underlyingConnection, optTradesReplication)

    val posListener = ListenerBinding(underlyingConnection, posReplication)

    val repliesListener = ListenerBinding(underlyingTradingConnection, Replies(publisherName))
  }


  class IntegrationEngine extends Engine with Connections with Replication with Publisher with Listeners {
    val ServicesActor = system.deadLetters
    val StrategiesActor = system.deadLetters
  }

  class IntegrationServices(val engine: IntegrationEngine) extends ServicesActor with ReplicationConnection with TradingConnection with InstrumentData with Portfolio with Trading

  "Trading Service" must {
    "fail buy bad contract" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartServices

      services ! SubscribeTransitionCallBack(TestActorRef(new Actor {
        protected def receive = {
          case Transition(_, _, ServicesState.Active) =>
            val trading = services.underlyingActor.service(Trading.Trading)
            log.info("Trading service = " + trading)

            implicit val timeout = Timeout(10.minutes)
            val f = (trading ? Buy(FutureContract(IsinId(0), Isin("RTS-9.12"), ShortIsin(""), ""), 1, 100)).mapTo[OrderExecution]

            f onComplete {
              res => log.info("Result = " + res)
            }
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }

    "add order and cancel it later" in {

      val underlyingEngine = TestActorRef(new IntegrationEngine, "Engine").underlyingActor
      val services = TestActorRef(new IntegrationServices(underlyingEngine), "Services")

      services ! StartServices

      services ! SubscribeTransitionCallBack(TestActorRef(new Actor {
        protected def receive = {
          case Transition(_, _, ServicesState.Active) =>
            val trading = services.underlyingActor.service(Trading.Trading)
            log.info("Trading service = " + trading)

            implicit val timeout = Timeout(10.minutes)
            val f1 = (trading ? Buy(FutureContract(IsinId(0), Isin("RTS-12.12"), ShortIsin(""), ""), 1, 143000, orderType = ImmediateOrCancel)).mapTo[OrderExecution]

            f1 onComplete (_ match {
              case Left(err) =>
                log.error("Error placing order; Error = " + err)

              case Right(execution) =>
                log.info("Execution report = " + execution)
                execution.subscribeOrderEvents(TestActorRef(new Actor {
                  protected def receive = {
                    case e => log.info("OrderEvent = " + e)
                  }
                }))
                val f2 = execution.cancel
                val cancelled = Await.result(f2, 5.seconds)

                log.info("Cancel Response = " + cancelled)
            })

        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
