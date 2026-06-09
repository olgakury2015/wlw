/**
 * WGS84 ↔ GCJ-02（国测局）近似转换，供 OpenStreetMap 底图展示高德/档案 GCJ 坐标。
 */
(function (global) {
    'use strict';

    var PI = Math.PI;
    var A = 6378245.0;
    var EE = 0.00669342162296594323;

    function outOfChina(lng, lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    function transformLat(lng, lat) {
        var ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat
            + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    function transformLng(lng, lat) {
        var ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng
            + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    function gcj02ToWgs84(lng, lat) {
        if (outOfChina(lng, lat)) {
            return { lng: lng, lat: lat };
        }
        var dLat = transformLat(lng - 105.0, lat - 35.0);
        var dLng = transformLng(lng - 105.0, lat - 35.0);
        var radLat = lat / 180.0 * PI;
        var magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        var sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return { lng: lng - dLng, lat: lat - dLat };
    }

    function wgs84ToGcj02(lng, lat) {
        if (outOfChina(lng, lat)) {
            return { lng: lng, lat: lat };
        }
        var dLat = transformLat(lng - 105.0, lat - 35.0);
        var dLng = transformLng(lng - 105.0, lat - 35.0);
        var radLat = lat / 180.0 * PI;
        var magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        var sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return { lng: lng + dLng, lat: lat + dLat };
    }

    /**
     * 将设备 pin（lat/lng + gcj02 标记）转为地图展示用 WGS84。
     */
    function pinToWgs84(pin) {
        if (!pin || typeof pin.lat !== 'number' || typeof pin.lng !== 'number') {
            return null;
        }
        if (pin.gcj02 === true) {
            return gcj02ToWgs84(pin.lng, pin.lat);
        }
        return { lng: pin.lng, lat: pin.lat };
    }

    global.WlwCoord = {
        gcj02ToWgs84: gcj02ToWgs84,
        wgs84ToGcj02: wgs84ToGcj02,
        pinToWgs84: pinToWgs84
    };
})(typeof window !== 'undefined' ? window : this);
