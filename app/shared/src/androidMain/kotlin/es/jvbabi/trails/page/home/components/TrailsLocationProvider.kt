package es.jvbabi.trails.page.home.components

import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.locationcomponent.LocationConsumer
import com.mapbox.maps.plugin.locationcomponent.LocationProvider
import es.jvbabi.trails.domain.repository.Location

/**
 * Feeds the location puck with the position Trails considers current — the latest
 * snapshot of this device — instead of the raw GPS fix Mapbox's default provider would
 * use. Trails only records a new snapshot once the device has moved far enough, so the
 * puck moves exactly when the position everyone else sees moves.
 *
 * Location and bearing are pushed via [update]; a consumer that registers later is
 * handed the last known values right away.
 */
class TrailsLocationProvider : LocationProvider {

    private val consumers = mutableSetOf<LocationConsumer>()
    private var location: Location? = null

    override fun registerLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.add(locationConsumer)
        location?.let { publish(locationConsumer, it) }
    }

    override fun unRegisterLocationConsumer(locationConsumer: LocationConsumer) {
        consumers.remove(locationConsumer)
    }

    /** Moves the puck to [location]. Null keeps the last one; there is nothing better to show. */
    fun update(location: Location?) {
        if (location == null || location == this.location) return
        this.location = location
        consumers.forEach { consumer -> publish(consumer, location) }
    }

    private fun publish(consumer: LocationConsumer, location: Location) {
        consumer.onLocationUpdated(Point.fromLngLat(location.longitude, location.latitude))
        consumer.onBearingUpdated(location.bearing.toDouble())
        consumer.onHorizontalAccuracyRadiusUpdated(location.locationAccuracy.toDouble())
    }
}
