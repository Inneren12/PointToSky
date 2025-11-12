package dev.pointtosky.wear

import android.content.Context
import android.content.Intent
import dev.pointtosky.wear.aim.core.AimTarget

const val ACTION_OPEN_AIM: String = "dev.pointtosky.action.OPEN_AIM"
const val ACTION_OPEN_AIM_LEGACY: String = "dev.pointtosky.ACTION_OPEN_AIM"
const val ACTION_OPEN_IDENTIFY: String = "dev.pointtosky.action.OPEN_IDENTIFY"
const val ACTION_OPEN_IDENTIFY_LEGACY: String = "dev.pointtosky.ACTION_OPEN_IDENTIFY"
const val EXTRA_AIM_TARGET_KIND: String = "extra_aim_target_kind"
const val EXTRA_AIM_RA_DEG: String = "extra_aim_ra_deg"
const val EXTRA_AIM_DEC_DEG: String = "extra_aim_dec_deg"
const val EXTRA_AIM_BODY: String = "extra_aim_body"
const val EXTRA_AIM_STAR_ID: String = "EXTRA_AIM_STAR_ID"

const val MAX_DEEP_LINK_PAYLOAD_BYTES: Int = 8 * 1024

fun buildOpenAimIntent(context: Context, target: AimTarget?): Intent {
    return Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_AIM
        target?.let { putAimTargetExtras(it) }
    }
}

fun buildOpenIdentifyIntent(context: Context): Intent {
    return Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_IDENTIFY
    }
}

fun Intent.putAimTargetExtras(target: AimTarget) {
    when (target) {
        is AimTarget.EquatorialTarget -> {
            putExtra(EXTRA_AIM_TARGET_KIND, "equatorial")
            putExtra(EXTRA_AIM_RA_DEG, target.eq.raDeg)
            putExtra(EXTRA_AIM_DEC_DEG, target.eq.decDeg)
        }
        is AimTarget.BodyTarget -> {
            putExtra(EXTRA_AIM_TARGET_KIND, "body")
            putExtra(EXTRA_AIM_BODY, target.body.name)
        }
        is AimTarget.StarTarget -> {
            putExtra(EXTRA_AIM_TARGET_KIND, "star")
            putExtra(EXTRA_AIM_STAR_ID, target.starId)
            target.eq?.let { eq ->
                putExtra(EXTRA_AIM_RA_DEG, eq.raDeg)
                putExtra(EXTRA_AIM_DEC_DEG, eq.decDeg)
            }
        }
}

}

