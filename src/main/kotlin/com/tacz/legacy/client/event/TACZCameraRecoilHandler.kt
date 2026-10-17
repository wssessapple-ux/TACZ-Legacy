package com.tacz.legacy.client.event

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.item.ItemStack
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction

@SideOnly(Side.CLIENT)
internal object TACZCameraRecoilHandler {
private const val SAMPLE_STEP_MILLIS = 5.0

private fun peakOf(spline: PolynomialSplineFunction?): Double {
    if (spline == null) return 0.0
    val knots = spline.knots
    val start = knots.first()
    val end = knots.last()
    if (!start.isFinite() || !end.isFinite() || end <= start) return 0.0
    var peak = 0.0
    var t = start
    while (t <= end) {
        val v = runCatching { spline.value(t) }.getOrNull() ?: 0.0
        if (kotlin.math.abs(v) > kotlin.math.abs(peak)) peak = v
        t += SAMPLE_STEP_MILLIS
    }
    return peak
}
    private var pitchSplineFunction: PolynomialSplineFunction? = null
    private var yawSplineFunction: PolynomialSplineFunction? = null
    private var shootTimestamp: Long = -1L
    private var lastLoggedShootTimestamp: Long = -1L
    


    @JvmStatic
    internal fun onLocalGunFire(player: EntityPlayerSP, stack: ItemStack) {
        val iGun = stack.item as? IGun ?: return
        val gunData = GunDataAccessor.getGunData(iGun.getGunId(stack)) ?: return
        val operator = IGunOperator.fromLivingEntity(player)
        val recoil = TACZGunPropertyResolver.resolveCameraRecoil(
            stack = stack,
            iGun = iGun,
            gunData = gunData,
            aimingProgress = operator.getSynAimingProgress(),
            isCrawling = operator.getDataHolder().isCrawling,
        ) ?: return
        pitchSplineFunction = recoil.pitch
        yawSplineFunction = recoil.yaw
        shootTimestamp = System.currentTimeMillis()
        player.rotationPitch -= peakOf(pitchSplineFunction).toFloat()
player.rotationYaw -= peakOf(yawSplineFunction).toFloat()
      
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    @JvmStatic
    internal fun onCameraSetup(event: EntityViewRenderEvent.CameraSetup) {
        val mc = Minecraft.getMinecraft()
        val player = mc.player ?: return
        if (event.entity != player) {
            return
        }
        val elapsed = System.currentTimeMillis() - shootTimestamp
        val elapsedMs = elapsed.toDouble()

        
    var pitch = event.pitch
var appliedPitchOffset = 0.0
pitchSplineFunction?.takeIf { it.isValidPoint(elapsedMs) }?.let { spline ->
    val value = spline.value(elapsedMs)
    appliedPitchOffset = value
    pitch -= value.toFloat()
}

       
            
           
            
 
        var yaw = event.yaw
var appliedYawOffset = 0.0
yawSplineFunction?.takeIf { it.isValidPoint(elapsedMs) }?.let { spline ->
    val value = spline.value(elapsedMs)
    appliedYawOffset = value
    yaw -= value.toFloat()
}

        

        if (System.getProperty("tacz.focusedSmoke", "false").toBoolean()
    && shootTimestamp > 0L
    && lastLoggedShootTimestamp != shootTimestamp
    && (kotlin.math.abs(appliedPitchOffset) > 1.0E-4 || kotlin.math.abs(appliedYawOffset) > 1.0E-4)
) {
    lastLoggedShootTimestamp = shootTimestamp
    com.tacz.legacy.TACZLegacy.logger.info(
        "[FocusedSmoke] CAMERA_RECOIL_APPLIED shootTimestamp={} pitchOffset={} yawOffset={}",
        shootTimestamp,
        "%.3f".format(appliedPitchOffset),
        "%.3f".format(appliedYawOffset),
    )
}

        event.pitch = pitch
        event.yaw = yaw
    }
}