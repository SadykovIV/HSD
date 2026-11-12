package com.hsd.universal

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationDistanceOuterClass.NavigationDistance
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver.NavigationType
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent.NextTurnEnum
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent.TurnSide
import aap_protobuf.service.navigationstatus.message.NavigationRoadOuterClass.NavigationRoad
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus.NavigationStatusEnum
import aap_protobuf.service.navigationstatus.message.NavigationStepDistanceOuterClass.NavigationStepDistance
import aap_protobuf.service.navigationstatus.message.NavigationStepOuterClass.NavigationStep
import com.google.protobuf.ByteString
import com.hsd.universal.aa.AaMeta
import com.hsd.universal.aa.AaPrefs
import com.hsd.universal.cluster.*
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel

/** Use the same opt-in sources as Desktop, bound to the cluster services offered by this receiver. */
internal class AutoCluster(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = AaPrefs(context)
    @Volatile private var closed = false
    @Volatile private var interest = GuidanceInterest(GuidanceInterest.Mode.NONE)
    private var media: ClusterMediaSource? = null
    private var guidance: ClusterNavigationSource? = null
    private var foreground: CarLifeForegroundGuidance? = null
    private var forgetEnabled: (() -> Unit)? = null
    private var forgetStreets: (() -> Unit)? = null
    private var navigating = false

    fun channels(services: List<Service>): List<ServiceChannel> {
        val mediaService = services.firstOrNull { it.hasMediaPlaybackService() }
        val mediaChannel = mediaService?.takeIf { prefs.metaMusic }?.let { descriptor ->
            MediaPlaybackChannel(descriptor) { channel -> main.post {
                if (!closed && media == null) media = ClusterMediaSource(context) { track ->
                    if (!closed && channel.opened) {
                        val meta = MediaPlaybackMetadata.newBuilder()
                        val status = MediaPlaybackStatus.newBuilder()
                        if (track == null) status.state = MediaPlaybackStatus.State.STOPPED
                        else {
                            meta.setSong(track.title).setArtist(track.artist).setAlbum(track.album)
                            track.durationSeconds?.let { meta.durationSeconds = it }
                            track.artwork?.let { meta.albumArt = ByteString.copyFrom(it.copyBytes()) }
                            status.setMediaSource(track.source).setState(if (track.playing) MediaPlaybackStatus.State.PLAYING else MediaPlaybackStatus.State.PAUSED)
                            track.positionSeconds?.let { status.playbackSeconds = it }
                        }
                        channel.metadata(meta.build())
                        channel.status(status.build())
                    }
                }.also { it.start() }
            } }
        }
        val navigation = services.firstOrNull { it.hasNavigationStatusService() }?.let { descriptor ->
            NavigationStatusChannel(descriptor) { channel, subscribed -> main.post {
                if (!closed) {
                    if (forgetEnabled == null) {
                        forgetStreets = prefs.observeNavigationStreetNames { guidance?.streetNamesChanged() }
                        forgetEnabled = prefs.observeNavigationHints { updateInterest(channel, channel.subscribed) }
                    }
                    updateInterest(channel, subscribed)
                }
            } }
        }
        return listOfNotNull(mediaChannel, navigation)
    }

    private fun updateInterest(channel: NavigationStatusChannel, subscribed: Boolean?) {
        val mode = when {
            !prefs.metaNavi || subscribed == false -> GuidanceInterest.Mode.NONE
            subscribed == true -> GuidanceInterest.Mode.ACTIVE
            else -> GuidanceInterest.Mode.REQUESTED
        }
        if (mode == interest.mode) return
        interest = GuidanceInterest(mode)
        if (mode == GuidanceInterest.Mode.NONE) {
            guidance?.close()
            guidance = null
            foreground?.close()
            foreground = null
            sendHint(channel, null)
        } else if (guidance == null) {
            foreground = if (Build.VERSION.SDK_INT >= 36) CarLifeForegroundGuidance(context) { interest } else null
            guidance = ClusterNavigationSource(context, { interest }, foreground?.let { it::request }) { hint ->
                main.post { if (!closed && channel.opened && interest.mode != GuidanceInterest.Mode.NONE) sendHint(channel, hint) }
            }.also { it.start() }
        } else guidance?.subscriptionChanged()
    }

    private fun sendHint(channel: NavigationStatusChannel, hint: ClusterNavigationHint?) {
        if (hint == null) {
            if (navigating) channel.status(NavigationStatusEnum.INACTIVE)
            navigating = false
            return
        }
        if (!navigating) channel.status(NavigationStatusEnum.ACTIVE)
        navigating = true
        val (modern, classic, side) = when (hint.nextTurn) {
            AaMeta.T_FRONT -> Triple(NavigationType.STRAIGHT, NextTurnEnum.STRAIGHT, TurnSide.UNSPECIFIED)
            AaMeta.T_LEFT -> Triple(NavigationType.TURN_NORMAL_LEFT, NextTurnEnum.TURN, TurnSide.LEFT)
            AaMeta.T_RIGHT -> Triple(NavigationType.TURN_NORMAL_RIGHT, NextTurnEnum.TURN, TurnSide.RIGHT)
            AaMeta.T_LEFT_FRONT -> Triple(NavigationType.TURN_SLIGHT_LEFT, NextTurnEnum.SLIGHT_TURN, TurnSide.LEFT)
            AaMeta.T_RIGHT_FRONT -> Triple(NavigationType.TURN_SLIGHT_RIGHT, NextTurnEnum.SLIGHT_TURN, TurnSide.RIGHT)
            AaMeta.T_LEFT_BACK -> Triple(NavigationType.TURN_SHARP_LEFT, NextTurnEnum.SHARP_TURN, TurnSide.LEFT)
            AaMeta.T_RIGHT_BACK -> Triple(NavigationType.TURN_SHARP_RIGHT, NextTurnEnum.SHARP_TURN, TurnSide.RIGHT)
            AaMeta.T_BACK -> Triple(NavigationType.U_TURN_LEFT, NextTurnEnum.U_TURN, TurnSide.UNSPECIFIED)
            AaMeta.T_RING -> Triple(NavigationType.ROUNDABOUT_ENTER, NextTurnEnum.ROUNDABOUT_ENTER, TurnSide.UNSPECIFIED)
            AaMeta.T_RING_OUT -> Triple(NavigationType.ROUNDABOUT_EXIT, NextTurnEnum.ROUNDABOUT_EXIT, TurnSide.UNSPECIFIED)
            AaMeta.T_START -> Triple(NavigationType.DEPART, NextTurnEnum.DEPART, TurnSide.UNSPECIFIED)
            AaMeta.T_DEST -> Triple(NavigationType.DESTINATION, NextTurnEnum.DESTINATION, TurnSide.UNSPECIFIED)
            else -> Triple(NavigationType.UNKNOWN, NextTurnEnum.UNKNOWN, TurnSide.UNSPECIFIED)
        }
        val road = if (prefs.metaNaviStreetNames) hint.road else ""
        val step = NavigationStep.newBuilder().setManeuver(NavigationManeuver.newBuilder().setType(modern))
        if (road.isNotEmpty()) step.setRoad(NavigationRoad.newBuilder().setName(road))
        val distance = NavigationNextTurnDistanceEvent.newBuilder()
        val stepDistance = NavigationStepDistance.newBuilder()
        hint.distanceMeters?.let { meters ->
            distance.distanceMeters = meters
            stepDistance.setDistance(NavigationDistance.newBuilder().setMeters(meters))
        }
        hint.timeSeconds?.let { seconds -> distance.timeToTurnSeconds = seconds; stepDistance.timeToStepSeconds = seconds.toLong() }
        channel.update(NavigationState.newBuilder().addSteps(step).build(),
            NavigationCurrentPosition.newBuilder().setStepDistance(stepDistance).build(),
            NavigationNextTurnEvent.newBuilder().setEvent(classic).setTurnSide(side).setRoad(road).build(), distance.build())
    }

    fun close() {
        closed = true
        forgetEnabled?.invoke()
        forgetStreets?.invoke()
        interest = GuidanceInterest(GuidanceInterest.Mode.NONE)
        guidance?.close()
        foreground?.close()
        media?.close()
    }
}
