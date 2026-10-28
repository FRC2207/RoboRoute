package com.team2207.roboroute.aoa

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.team2207.roboroute.datastore.Pose2d

object AoaActionSender {
    private val gson = Gson()

    suspend fun putValue(
        key: String,
        value: Any,
        schema: String? = null,
    ) {
        val message =
            mutableMapOf<String, Any>(
                "action" to "put",
                "key" to key,
                "value" to value,
            )
        if (schema != null) {
            message["schema"] = schema
        }

        val json = gson.toJson(message)
        AoaPoseReceiver.instance?.sendJson(json)
    }

    suspend fun runPose(pose: Pose2d) {
        val jsonStructure =
            JsonObject().apply {
                addProperty("type", "POSE")
                add(
                    "pose",
                    JsonObject().apply {
                        addProperty("x", pose.x)
                        addProperty("y", pose.y)
                        addProperty("rotation", pose.rotation)
                    },
                )
            }

        putValue(key = "/RoboRoute/Plan", value = jsonStructure.toString())
    }

    suspend fun runRoute(route: List<Pose2d>) {
        if (route.isEmpty()) return

        val plan =
            JsonObject().apply {
                addProperty("type", "ROUTE")
                add(
                    "routePoses",
                    JsonArray().apply {
                        route.forEach { pose ->
                            add(
                                JsonObject().apply {
                                    addProperty("x", pose.x)
                                    addProperty("y", pose.y)
                                    addProperty("rotation", pose.rotation)
                                },
                            )
                        }
                    },
                )
            }

        putValue(key = "/RoboRoute/Plan", value = plan.toString())
    }
}
