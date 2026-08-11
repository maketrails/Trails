package es.jvbabi.trails.routes

import es.jvbabi.trails.database.DatabaseManager
import es.jvbabi.trails.database.mapper.toApi
import es.jvbabi.trails.routes.active_share.item.getActiveShare
import es.jvbabi.trails.routes.active_share.item.history.getActiveShareHistory
import es.jvbabi.trails.routes.active_share.item.returnActiveShare
import es.jvbabi.trails.routes.active_share.shareSnapshotSocket
import es.jvbabi.trails.routes.app.app
import es.jvbabi.trails.routes.app.bulkCheckActiveShares
import es.jvbabi.trails.routes.app.session_healthcheck.sessionHealthCheck
import es.jvbabi.trails.routes.auth.app_authorization.appAuthorization
import es.jvbabi.trails.routes.auth.webapp.webappAuthorization
import es.jvbabi.trails.routes.auth.webapp.webappLogout
import es.jvbabi.trails.routes.devices.devices
import es.jvbabi.trails.routes.devices.image.deviceImage
import es.jvbabi.trails.routes.devices.item.deleteDevice
import es.jvbabi.trails.routes.devices.item.getDevice
import es.jvbabi.trails.routes.devices.item.history.getDeviceHistory
import es.jvbabi.trails.routes.devices.item.optimization.getDeviceOptimization
import es.jvbabi.trails.routes.devices.item.optimization.reoptimizeDevice
import es.jvbabi.trails.routes.devices.item.pingDevice
import es.jvbabi.trails.routes.devices.item.ringDevice
import es.jvbabi.trails.routes.devices.item.stopRingDevice
import es.jvbabi.trails.routes.devices.item.updateDevice
import es.jvbabi.trails.routes.ring.ringSocket
import es.jvbabi.trails.routes.me.emitted_shares.getEmittedShares
import es.jvbabi.trails.routes.me.me
import es.jvbabi.trails.routes.me.shares.deleteUserShare
import es.jvbabi.trails.routes.me.shares.getUserShares
import es.jvbabi.trails.routes.me.shares.registerUserShare
import es.jvbabi.trails.routes.share.createShare
import es.jvbabi.trails.routes.share.item.deleteShare
import es.jvbabi.trails.routes.share.item.getShare
import es.jvbabi.trails.routes.share.item.redeem.redeemShare
import es.jvbabi.trails.routes.share.item.updateShare
import es.jvbabi.trails.routes.user.item.getUser
import es.jvbabi.trails.routes.webapp.mapbox.webappMapbox
import es.jvbabi.trails.routes.webapp.me.webappMe
import es.jvbabi.trails.routes.webapp.optimization.webappOptimizationSocket
import es.jvbabi.trails.routes.webapp.webappSocket
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.installRouting() {
    val db by inject<DatabaseManager>()

    routing {
        route("/api/v1") {
            route("/auth") {
                route("/app-authorization") {
                    appAuthorization()
                }

                route("/webapp-authorization") {
                    webappAuthorization()
                }
            }

            route("/me") {
                me()

                route("/shares") {
                    getUserShares()

                    route("/register") {
                        registerUserShare()
                    }

                    route("/{shareId}") {
                        deleteUserShare()
                    }
                }

                route("/emitted-shares") {
                    getEmittedShares()
                }
            }

            route("/devices") {
                devices()

                route("/{deviceId}") {
                    get {
                        val device = call.getDevice()
                        call.respond(db.transaction { device.toApi() })
                    }

                    updateDevice()

                    deleteDevice()

                    route("/history") {
                        getDeviceHistory()
                    }

                    route("/optimization") {
                        getDeviceOptimization()

                        route("/reoptimize") {
                            reoptimizeDevice()
                        }
                    }

                    route("/ping") {
                        pingDevice()
                    }

                    route("/ring") {
                        ringDevice()

                        route("/ws") {
                            ringSocket()
                        }

                        route("/stop") {
                            stopRingDevice()
                        }
                    }
                }
            }

            route("/share") {
                createShare()

                route("/{shareId}") {
                    get {
                        val share = call.getShare()
                        call.respond(db.transaction { share.toApi() })
                    }

                    updateShare()

                    deleteShare()

                    route("/redeem") {
                        redeemShare()
                    }
                }
            }

            route("/active-shares") {
                route("/ws") {
                    shareSnapshotSocket()
                }

                route("/{activeShareId}") {
                    get {
                        val activeShare = call.getActiveShare()
                        call.respond(db.transaction { activeShare.toApi() })
                    }

                    route("/history") {
                        getActiveShareHistory()
                    }

                    route("/return") {
                        returnActiveShare()
                    }
                }
            }

            route("/users") {
                route("/{userId}") {
                    get {
                        val user = call.getUser()
                        call.respond(db.transaction { user.toApi() })
                    }
                }
            }

            route("/app") {
                app()

                route("/session-healthcheck") {
                    sessionHealthCheck()
                }

                route("/active-shares") {
                    route("/bulk-check") {
                        bulkCheckActiveShares()
                    }
                }
            }

            route("/webapp") {

                route("/ws") {
                    webappSocket()
                }

                route("/me") {
                    webappMe()
                }

                route("/optimization") {
                    route("/ws") {
                        webappOptimizationSocket()
                    }
                }

                route("/mapbox") {
                    webappMapbox()
                }

                route("/auth") {
                    route("/logout") {
                        webappLogout()
                    }
                }
            }

            route("/devices") {
                route("/image") {
                    deviceImage()
                }
            }
        }
    }
}
