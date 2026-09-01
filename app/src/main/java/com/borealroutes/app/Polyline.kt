package com.borealroutes.app

object Polyline {
    fun decodeMotis(points: String): List<GeoPoint> {
        if (points.isBlank()) return emptyList()
        val out = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        try {
            while (index < points.length) {
                var result = 0
                var shift = 0
                var b: Int
                do {
                    b = points[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index <= points.length)
                lat += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

                result = 0
                shift = 0
                do {
                    b = points[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index <= points.length)
                lon += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

                val a = lat / 1e6
                val o = lon / 1e6
                if (a.isFinite() && o.isFinite() && kotlin.math.abs(a) <= 90 && kotlin.math.abs(o) <= 180) {
                    out += GeoPoint(a, o)
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return out
    }
}
