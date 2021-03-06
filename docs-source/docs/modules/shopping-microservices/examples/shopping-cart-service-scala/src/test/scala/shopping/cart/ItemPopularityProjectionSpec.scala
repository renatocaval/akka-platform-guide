package shopping.cart

import java.time.Instant

import scala.concurrent.Future

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.query.Offset
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.testkit.TestProjection
import akka.projection.testkit.TestSourceProvider
import akka.projection.testkit.scaladsl.ProjectionTestKit
import org.scalatest.wordspec.AnyWordSpecLike

object ItemPopularityProjectionSpec {
  // stub out the db layer and simulate recording item count updates
  class TestItemPopularityRepository extends ItemPopularityRepository {
    var counts: Map[String, Long] = Map.empty

    override def update(itemId: String, delta: Int): Future[Done] =
      Future.successful {
        counts = counts + (itemId -> (counts.getOrElse(itemId, 0L) + delta))
        Done
      }

    override def getItem(itemId: String): Future[Option[Long]] =
      Future.successful(counts.get(itemId))
  }
}

class ItemPopularityProjectionSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  import ItemPopularityProjectionSpec.TestItemPopularityRepository

  private val projectionTestKit = ProjectionTestKit(testKit)

  private def createEnvelope(event: ShoppingCart.Event, seqNo: Long, timestamp: Long = 0L) =
    EventEnvelope(Offset.sequence(seqNo), "persistenceId", seqNo, event, timestamp)

  "The events from the Shopping Cart" should {

    "update item popularity counts by the projection" in {

      // FIXME this will be a Source in Projections 1.0.0
      val events =
        List[EventEnvelope[ShoppingCart.Event]](
          createEnvelope(ShoppingCart.ItemAdded("a7098", "bowling shoes", 1), 0L),
          createEnvelope(ShoppingCart.ItemQuantityAdjusted("a7098", "bowling shoes", 2, 1), 1L),
          createEnvelope(ShoppingCart.CheckedOut("a7098", Instant.parse("2020-01-01T12:00:00.00Z")), 2L),
          createEnvelope(ShoppingCart.ItemAdded("0d12d", "akka t-shirt", 1), 3L),
          createEnvelope(ShoppingCart.ItemAdded("0d12d", "skis", 1), 4L),
          createEnvelope(ShoppingCart.ItemRemoved("0d12d", "skis", 1), 5L),
          createEnvelope(ShoppingCart.CheckedOut("0d12d", Instant.parse("2020-01-01T12:05:00.00Z")), 6L))

      val repository = new TestItemPopularityRepository
      val projectionId = ProjectionId("item-popularity", "carts-0")
      val sourceProvider =
        TestSourceProvider[Offset, EventEnvelope[ShoppingCart.Event]](events, extractOffset = env => env.offset)
      // FIXME different signature in Projections 1.0.0
      val projection =
        TestProjection[Offset, EventEnvelope[ShoppingCart.Event]](
          system,
          projectionId,
          sourceProvider,
          Offset.noOffset,
          new ItemPopularityProjectionHandler("carts-0", system, repository))

      projectionTestKit.run(projection) {
        repository.counts shouldBe Map("bowling shoes" -> 2, "akka t-shirt" -> 1, "skis" -> 0)
      }
    }
  }

}
