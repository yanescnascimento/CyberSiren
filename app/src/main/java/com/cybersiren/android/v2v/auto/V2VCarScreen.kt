package com.cybersiren.android.v2v.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.cybersiren.android.R
import com.cybersiren.android.v2v.model.AlertMode
import com.cybersiren.android.v2v.model.ReceivedAlert
import com.cybersiren.android.v2v.model.UrgencyLevel
import com.cybersiren.android.v2v.model.VehicleType
import com.cybersiren.android.v2v.ui.localized
import kotlinx.coroutines.*

class V2VHomeScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    init {
        marker = MARKER_HOME
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        refreshJob = scope.launch {
            while (isActive) {
                invalidate()
                delay(REFRESH_MS)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        refreshJob?.cancel()
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val service = V2VCarServiceHolder.getService()
        val selected = service?.getSelectedVehicleType() ?: VehicleType.AMBULANCE
        val peers = service?.getConnectedPeers() ?: 0

        val listBuilder = ItemList.Builder()
        VehicleType.values().forEach { type ->
            listBuilder.addItem(buildVehicleGridItem(type, isSelected = type == selected))
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.localized(R.string.v2v_car_action_receive))
                    .setIcon(iconOf(carContext, R.drawable.ic_car_hearing, CarColor.DEFAULT))
                    .setOnClickListener {
                        V2VCarServiceHolder.getService()?.setMode(AlertMode.RECEIVER)
                        screenManager.push(V2VReceiverScreen(carContext))
                    }
                    .build()
            )
            .build()

        val title = when {
            peers <= 0 -> carContext.localized(R.string.v2v_car_status_listening)
            peers == 1 -> carContext.localized(R.string.v2v_car_status_connected_one)
            else -> carContext.localized(R.string.v2v_car_status_connected_many, peers)
        }

        return GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun buildVehicleGridItem(type: VehicleType, isSelected: Boolean): GridItem {
        val color = carColorFor(type)
        val icon = iconOf(carContext, drawableFor(type), color)
        val subtitle = carContext.localized(
            if (isSelected) R.string.v2v_car_grid_selected else R.string.v2v_car_grid_tap_to_use
        )

        return GridItem.Builder()
            .setTitle(type.localizedName(carContext))
            .setText(subtitle)
            .setImage(icon, GridItem.IMAGE_TYPE_ICON)
            .setOnClickListener {
                V2VCarServiceHolder.getService()?.apply {
                    selectVehicleType(type)
                    setMode(AlertMode.SENDER)
                }
                screenManager.push(V2VSenderScreen(carContext))
            }
            .build()
    }
}

class V2VSenderScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    init {
        marker = MARKER_SENDER
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        refreshJob = scope.launch {
            while (isActive) {
                invalidate()
                delay(REFRESH_MS_FAST)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        refreshJob?.cancel()
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val service = V2VCarServiceHolder.getService()
        val vehicle = service?.getSelectedVehicleType() ?: VehicleType.AMBULANCE
        val isActive = service?.isEmergencyActive() == true
        val peers = service?.getConnectedPeers() ?: 0
        val speed = service?.getCurrentSpeedKmh() ?: 0f
        val heading = service?.getCurrentHeadingDegrees() ?: 0f
        val lat = service?.getCurrentLatitude()
        val lon = service?.getCurrentLongitude()

        val color = carColorFor(vehicle)
        val statusTitle = carContext.localized(
            if (isActive) R.string.v2v_car_sender_active else R.string.v2v_car_sender_ready
        )
        val metricsLine1 = "${speed.toInt()} km/h · ${headingText(heading)} (${heading.toInt()}°)"
        val metricsLine2 = buildString {
            append(
                if (peers == 1) carContext.localized(R.string.v2v_car_peer_one)
                else carContext.localized(R.string.v2v_car_peer_many, peers)
            )
            if (lat != null && lon != null) {
                append(" · ")
                append(String.format("%.4f, %.4f", lat, lon))
            }
        }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("${vehicle.localizedName(carContext).uppercase()}  ·  $statusTitle")
                    .addText(metricsLine1)
                    .addText(metricsLine2)
                    .setImage(iconOf(carContext, drawableFor(vehicle), color))
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.localized(
                        if (isActive) R.string.v2v_car_btn_stop else R.string.v2v_car_btn_activate
                    ))
                    .setBackgroundColor(if (isActive) CarColor.RED else color)
                    .setIcon(iconOf(carContext, R.drawable.ic_car_campaign, CarColor.DEFAULT))
                    .setOnClickListener {
                        service?.toggleEmergencyBroadcast()
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.localized(R.string.v2v_car_action_receive))
                    .setIcon(iconOf(carContext, R.drawable.ic_car_hearing, CarColor.DEFAULT))
                    .setOnClickListener {
                        V2VCarServiceHolder.getService()?.setMode(AlertMode.RECEIVER)
                        screenManager.push(V2VReceiverScreen(carContext))
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(vehicle.localizedName(carContext))
            .setHeaderAction(Action.BACK)
            .build()
    }
}

class V2VReceiverScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    init {
        marker = MARKER_RECEIVER
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        refreshJob = scope.launch {
            while (isActive) {
                invalidate()
                delay(REFRESH_MS_FAST)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        refreshJob?.cancel()
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val service = V2VCarServiceHolder.getService()
        val alerts = (service?.getActiveAlerts() ?: emptyList()).sortedBy { it.distanceMeters }
        val peers = service?.getConnectedPeers() ?: 0

        return if (alerts.isEmpty()) buildEmpty(peers) else buildWithAlerts(alerts, peers)
    }

    private fun buildEmpty(peers: Int): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(carContext.localized(R.string.v2v_car_clear_road))
                    .addText(carContext.localized(R.string.v2v_car_clear_road_desc))
                    .addText(
                        if (peers == 1) carContext.localized(R.string.v2v_car_peer_one)
                        else carContext.localized(R.string.v2v_car_peer_many, peers)
                    )
                    .setImage(iconOf(carContext, R.drawable.ic_car_check, CarColor.GREEN))
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.localized(R.string.v2v_car_action_transmit))
                    .setIcon(iconOf(carContext, R.drawable.ic_car_campaign, CarColor.DEFAULT))
                    .setOnClickListener {
                        V2VCarServiceHolder.getService()?.setMode(AlertMode.SENDER)
                        screenManager.popToRoot()
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(carContext.localized(R.string.v2v_car_action_receive))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun buildWithAlerts(alerts: List<ReceivedAlert>, peers: Int): Template {
        val top = alerts.first()
        val color = carColorFor(top.alert.vehicleType)

        val urgencyPrefix = when (top.urgencyLevel) {
            UrgencyLevel.CRITICAL -> carContext.localized(R.string.v2v_car_urgency_critical_prefix)
            UrgencyLevel.HIGH -> carContext.localized(R.string.v2v_car_urgency_high_prefix)
            else -> ""
        }

        val peersShort = if (peers == 1) carContext.localized(R.string.v2v_car_peer_short_one)
            else carContext.localized(R.string.v2v_car_peer_short_many, peers)

        val paneBuilder = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("${top.alert.vehicleType.localizedName(carContext).uppercase()}  ·  ${top.distanceDisplay}")
                    .addText("$urgencyPrefix${directionText(carContext, top.relativeDirection)} · ${top.alert.speedKmh.toInt()} km/h")
                    .addText("${ageText(carContext, top.ageSeconds)} · $peersShort")
                    .setImage(iconOf(carContext, drawableFor(top.alert.vehicleType), color))
                    .build()
            )

        alerts.drop(1).take(2).forEach { alert ->
            val c = carColorFor(alert.alert.vehicleType)
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("${alert.alert.vehicleType.localizedName(carContext)}  ·  ${alert.distanceDisplay}")
                    .addText("${directionText(carContext, alert.relativeDirection)} · ${alert.alert.speedKmh.toInt()} km/h")
                    .setImage(iconOf(carContext, drawableFor(alert.alert.vehicleType), c))
                    .build()
            )
        }

        paneBuilder.addAction(
            Action.Builder()
                .setTitle(carContext.localized(R.string.v2v_car_action_transmit))
                .setIcon(iconOf(carContext, R.drawable.ic_car_campaign, CarColor.DEFAULT))
                .setOnClickListener {
                    V2VCarServiceHolder.getService()?.setMode(AlertMode.SENDER)
                    screenManager.popToRoot()
                }
                .build()
        )

        val headerTitle = when (top.urgencyLevel) {
            UrgencyLevel.CRITICAL -> carContext.localized(R.string.v2v_car_header_critical, alerts.size)
            else -> if (alerts.size == 1)
                carContext.localized(R.string.v2v_car_header_alert_one, alerts.size)
            else
                carContext.localized(R.string.v2v_car_header_alert_many, alerts.size)
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(headerTitle)
            .setHeaderAction(Action.BACK)
            .build()
    }
}

private const val MARKER_HOME = "home"
private const val MARKER_SENDER = "sender"
private const val MARKER_RECEIVER = "receiver"
private const val REFRESH_MS = 2000L
private const val REFRESH_MS_FAST = 1200L

private fun drawableFor(type: VehicleType): Int = when (type) {
    VehicleType.AMBULANCE -> R.drawable.ic_car_ambulance
    VehicleType.FIRE_TRUCK -> R.drawable.ic_car_fire
    VehicleType.POLICE_CAR -> R.drawable.ic_car_police
    VehicleType.EMERGENCY -> R.drawable.ic_car_warning
}

private fun carColorFor(type: VehicleType): CarColor = when (type) {
    VehicleType.AMBULANCE -> CarColor.createCustom(0xFF2563EB.toInt(), 0xFF60A5FA.toInt())
    VehicleType.FIRE_TRUCK -> CarColor.createCustom(0xFFDC2626.toInt(), 0xFFF87171.toInt())
    VehicleType.POLICE_CAR -> CarColor.createCustom(0xFF1E3A8A.toInt(), 0xFF60A5FA.toInt())
    VehicleType.EMERGENCY -> CarColor.createCustom(0xFFF59E0B.toInt(), 0xFFFBBF24.toInt())
}

private fun iconOf(context: CarContext, resId: Int, tint: CarColor): CarIcon {
    val iconCompat = IconCompat.createWithResource(context, resId)
    return CarIcon.Builder(iconCompat).setTint(tint).build()
}

private fun directionText(context: CarContext, direction: String): String = context.localized(
    when (direction.lowercase()) {
        "ahead" -> R.string.v2v_car_dir_ahead
        "behind" -> R.string.v2v_car_dir_behind
        "left" -> R.string.v2v_car_dir_left
        "right" -> R.string.v2v_car_dir_right
        else -> R.string.v2v_car_dir_unknown
    }
)

private fun headingText(heading: Float): String = when {
    heading >= 337.5 || heading < 22.5 -> "N"
    heading < 67.5 -> "NE"
    heading < 112.5 -> "E"
    heading < 157.5 -> "SE"
    heading < 202.5 -> "S"
    heading < 247.5 -> "SW"
    heading < 292.5 -> "W"
    heading < 337.5 -> "NW"
    else -> "?"
}

private fun ageText(context: CarContext, seconds: Long): String = when {
    seconds < 5 -> context.localized(R.string.v2v_car_age_now)
    seconds < 60 -> context.localized(R.string.v2v_car_age_seconds, seconds.toInt())
    else -> context.localized(R.string.v2v_car_age_minutes, (seconds / 60).toInt())
}
