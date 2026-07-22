package fi.marmorikatu.app.house3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A tiny, allocation-frugal 3D math kit for the software renderer — just enough
 * linear algebra to orbit a camera and project the house's ~16k triangles.
 *
 * Matrices are 16-float, row-major (`m[row * 4 + col]`), and transform column
 * vectors: `v' = M · v`. This is the same convention the reference three.js
 * viewer uses, so the orbit/light math in the house-model README ports directly.
 */

/** A 3D point/direction. Kept as a value class of three floats. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-6f) this else Vec3(x / l, y / l, z / l)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

fun lerp(a: Vec3, b: Vec3, t: Float) =
    Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** `easeInOutQuad`, the tween curve the reference viewer uses for orbit moves. */
fun easeInOutQuad(t: Float): Float =
    if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f

/** Row-major 4×4 matrix helpers operating on plain [FloatArray]s of length 16. */
object Mat4 {
    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** `out = a · b`. */
    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[r * 4 + k] * b[k * 4 + c]
                out[r * 4 + c] = s
            }
        }
        return out
    }

    /**
     * Right-handed look-at, matching OpenGL/three.js: the camera sits at [eye]
     * looking toward [center] with [up] roughly vertical.
     */
    fun lookAt(eye: Vec3, center: Vec3, up: Vec3, out: FloatArray = FloatArray(16)): FloatArray {
        var fx = center.x - eye.x
        var fy = center.y - eye.y
        var fz = center.z - eye.z
        val forwardLength = sqrt(fx * fx + fy * fy + fz * fz)
        if (forwardLength > 1e-6f) {
            fx /= forwardLength; fy /= forwardLength; fz /= forwardLength
        }

        var sx = fy * up.z - fz * up.y
        var sy = fz * up.x - fx * up.z
        var sz = fx * up.y - fy * up.x
        var sideLength = sqrt(sx * sx + sy * sy + sz * sz)
        if (sideLength < 1e-6f) {
            // Pick a deterministic alternate up axis for a straight-down camera.
            if (kotlin.math.abs(fx) < 0.9f) {
                sx = 0f; sy = fz; sz = -fy
            } else {
                sx = -fz; sy = 0f; sz = fx
            }
            sideLength = sqrt(sx * sx + sy * sy + sz * sz)
        }
        if (sideLength > 1e-6f) {
            sx /= sideLength; sy /= sideLength; sz /= sideLength
        }
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        out[0] = sx; out[1] = sy; out[2] = sz; out[3] = -(sx * eye.x + sy * eye.y + sz * eye.z)
        out[4] = ux; out[5] = uy; out[6] = uz; out[7] = -(ux * eye.x + uy * eye.y + uz * eye.z)
        out[8] = -fx; out[9] = -fy; out[10] = -fz; out[11] = fx * eye.x + fy * eye.y + fz * eye.z
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    /** Right-handed perspective projecting to clip space with z in [-1, 1]. */
    fun perspective(
        fovyRad: Float,
        aspect: Float,
        near: Float,
        far: Float,
        out: FloatArray = FloatArray(16),
    ): FloatArray {
        val f = 1f / tan(fovyRad / 2f)
        out.fill(0f)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = (2f * far * near) / (near - far)
        out[14] = -1f
        return out
    }
}

/**
 * Transforms the point (x,y,z,1) by [m], writing clip-space x,y,z,w into [out].
 * Kept as a raw-array method so the hot projection loop avoids [Vec3] churn.
 */
fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
    out[offset] = m[0] * x + m[1] * y + m[2] * z + m[3]
    out[offset + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]
    out[offset + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
    out[offset + 3] = m[12] * x + m[13] * y + m[14] * z + m[15]
}

/**
 * Camera position on the orbit sphere, per the house-model README:
 * `pos = target + r·(sinφ·cosθ, cosφ, sinφ·sinθ)`.
 */
fun orbitEye(target: Vec3, theta: Float, phi: Float, radius: Float): Vec3 {
    val sp = sin(phi)
    return Vec3(
        target.x + radius * sp * cos(theta),
        target.y + radius * cos(phi),
        target.z + radius * sp * sin(theta),
    )
}
