package com.ergodicity.engine.plaza2

import akka.actor.ActorSystem
import RepositoryState._
import com.ergodicity.engine.plaza2.scheme.FutInfo._
import org.mockito.Mockito._
import plaza2._
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import com.ergodicity.engine.plaza2.Repository.{SubscribeSnapshots, Snapshot}
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import akka.event.Logging

class RepositorySpec  extends TestKit(ActorSystem()) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  "Repository" must {
    "be initialized in Idle state" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      log.info("State: " + repository.stateName)
      assert(repository.stateName == Idle)
    }

    "go to Synchronizing state as stream data begins" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      repository ! StreamDataBegin
      assert(repository.stateName == Synchronizing)
    }

    "receive snapshot immediately in Consistent state" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      val rec = mock(classOf[SessionRecord])
      repository.setState(Consistent, Map(1l -> rec))

      repository ! SubscribeSnapshots(self)
      expectMsgType[Snapshot[SessionRecord]]
    }

    "handle new data" in {
      val repository = TestFSMRef(Repository[SessionRecord], "Repository")
      repository.setState(Consistent, Map())

      repository ! SubscribeSnapshots(self)      
      repository ! StreamDataBegin
      assert(repository.stateName == Synchronizing)

      // Add two records
      repository ! StreamDataInserted("session", mockRecord(1, 1, 0, 111))
      repository ! StreamDataInserted("session", mockRecord(2, 2, 0, 112))
      assert(repository.stateData.size == 2)

      // Delete one of them
      repository ! StreamDataDeleted("session", 2, mockRecord(2, 2, 0, 112))
      assert(repository.stateData.size == 1)

      // Then insert another one
      repository ! StreamDataInserted("session", mockRecord(3, 3, 0, 113))
      assert(repository.stateData.size == 2)

      // Close transaction
      repository ! StreamDataEnd
      assert(repository.stateName == RepositoryState.Consistent)
      expectMsgType[Snapshot[SessionRecord]]

      // Remove old data
      repository ! StreamDatumDeleted("session", 3)
      log.info("Data: "+repository.stateData)
      assert(repository.stateData.size == 1)
    }
  }

  private def mockRecord(replID: Long, replRev: Long, replAct: Long, sessionId: Long) = {
    val rec = mock(classOf[Record])
    when(rec.getLong("replID")).thenReturn(replID);
    when(rec.getLong("replRev")).thenReturn(replRev);
    when(rec.getLong("replAct")).thenReturn(replAct);
    when(rec.getLong("sess_id")).thenReturn(sessionId);
    rec
  }

}