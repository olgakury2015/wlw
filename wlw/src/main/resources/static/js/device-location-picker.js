/**
 * 设备表单：省市区联动 + 地图选点（高德 GCJ-02 或 OpenStreetMap WGS84）。
 * 依赖 window.WLW_DL（由 fragments-device-location 注入）。
 */
(function () {
    'use strict';

    function qs(id) {
        return document.getElementById(id);
    }

    var map;
    var marker;
    var cascadeGeocodeSeq = 0;
    var pendingCascadeGeocode = false;

    function cfg() {
        return window.WLW_DL || {};
    }

    function isOsm() {
        return cfg().mapProvider === 'osm';
    }

    function isAmap() {
        return cfg().mapProvider === 'gaode';
    }

    function osmCfg() {
        return window.WLW_MAP_OSM || {};
    }

    /** 天地图底图时为 GCJ-02，与高德选点一致 */
    function isOsmGcjMap() {
        return isOsm() && !!osmCfg().useGcjMap;
    }

    function setGcjFlag(on) {
        var h = qs('dlManualGcj');
        if (h) {
            h.value = on ? 'true' : 'false';
        }
    }

    function setLatLng(lat, lng) {
        var la = qs('dlLat');
        var ln = qs('dlLng');
        if (la) {
            la.value = String(lat);
        }
        if (ln) {
            ln.value = String(lng);
        }
    }

    function syncDistrictPath() {
        var p = qs('dlProv');
        var c = qs('dlCity');
        var d = qs('dlDist');
        var h = qs('dlDistrictPath');
        if (!h || !p || !c || !d) {
            return;
        }
        var parts = [];
        if (p.selectedIndex > 0) {
            parts.push(p.options[p.selectedIndex].text);
        }
        if (c.selectedIndex > 0) {
            parts.push(c.options[c.selectedIndex].text);
        }
        if (d.selectedIndex > 0) {
            parts.push(d.options[d.selectedIndex].text);
        }
        h.value = parts.join('·');
    }

    function buildCascadeGeocodeAddress() {
        var p = qs('dlProv');
        var c = qs('dlCity');
        var d = qs('dlDist');
        if (!p || p.selectedIndex <= 0) {
            return '';
        }
        var parts = [];
        parts.push(p.options[p.selectedIndex].text);
        if (c && c.value && c.selectedIndex > 0) {
            var cn = c.options[c.selectedIndex].text;
            if (cn && cn.indexOf('—') !== 0 && cn !== '市辖区') {
                parts.push(cn);
            }
        }
        if (d && d.value && d.selectedIndex > 0) {
            var dn = d.options[d.selectedIndex].text;
            if (dn && dn.indexOf('—') !== 0) {
                parts.push(dn);
            }
        }
        return parts.join('');
    }

    function cascadeZoomForSelectId(selId) {
        if (selId === 'dlProv') {
            return 6.5;
        }
        if (selId === 'dlCity') {
            return 9.5;
        }
        return 12.5;
    }

    function deepestSelectWithValue() {
        var dist = qs('dlDist');
        var city = qs('dlCity');
        var prov = qs('dlProv');
        if (dist && dist.value) {
            return dist;
        }
        if (city && city.value) {
            return city;
        }
        if (prov && prov.value) {
            return prov;
        }
        return null;
    }

    /** 行政区中心点（接口多为 GCJ-02）→ 当前地图坐标系下的 lat/lng */
    function mapCoordsFromDistrictCenter(lng, lat) {
        if (isOsmGcjMap()) {
            return { lng: lng, lat: lat, gcj: true };
        }
        if (isOsm() && window.WlwCoord) {
            var w = window.WlwCoord.gcj02ToWgs84(lng, lat);
            return { lng: w.lng, lat: w.lat, gcj: false };
        }
        return { lng: lng, lat: lat, gcj: true };
    }

    function mapCoordsFromStored(lng, lat, storedGcj) {
        if (isOsmGcjMap() && window.WlwCoord) {
            if (storedGcj) {
                return { lng: lng, lat: lat };
            }
            var g = window.WlwCoord.wgs84ToGcj02(lng, lat);
            return { lng: g.lng, lat: g.lat };
        }
        if (isOsm()) {
            if (storedGcj && window.WlwCoord) {
                var w = window.WlwCoord.gcj02ToWgs84(lng, lat);
                return { lng: w.lng, lat: w.lat };
            }
            return { lng: lng, lat: lat };
        }
        if (!storedGcj && typeof AMap !== 'undefined' && typeof AMap.convertFrom === 'function') {
            return { lng: lng, lat: lat, needAmapConvert: true };
        }
        return { lng: lng, lat: lat };
    }

    function setMapView(lat, lng, zoom) {
        if (!map) {
            return;
        }
        if (isOsm() && typeof map.setView === 'function') {
            map.setView([lat, lng], zoom);
        } else if (isAmap() && typeof map.setZoomAndCenter === 'function' && typeof AMap !== 'undefined') {
            map.setZoomAndCenter(zoom, new AMap.LngLat(lng, lat));
        }
    }

    function applyCenterFromCascade() {
        var sel = deepestSelectWithValue();
        if (!sel || !sel.value) {
            syncDistrictPath();
            return;
        }
        var opt = sel.options[sel.selectedIndex];
        if (!opt) {
            syncDistrictPath();
            return;
        }
        syncDistrictPath();
        var lng = parseFloat(opt.getAttribute('data-lng'));
        var lat = parseFloat(opt.getAttribute('data-lat'));
        if (!isNaN(lat) && !isNaN(lng)) {
            var mc = mapCoordsFromDistrictCenter(lng, lat);
            setLatLng(mc.lat, mc.lng);
            setGcjFlag(mc.gcj);
            moveMarkerTo(mc.lng, mc.lat);
            setMapView(mc.lat, mc.lng, cascadeZoomForSelectId(sel.id));
            return;
        }
        applyGeocodeForCascade(sel);
    }

    function applyGeocodeForCascade(deepestSel) {
        if (!cfg().hasMap || !map) {
            if (cfg().hasMap) {
                pendingCascadeGeocode = true;
            }
            return;
        }
        var addr = buildCascadeGeocodeAddress();
        if (!addr) {
            return;
        }
        var mySeq = ++cascadeGeocodeSeq;

        if (isAmap() && typeof AMap !== 'undefined') {
            AMap.plugin('AMap.Geocoder', function () {
                if (cascadeGeocodeSeq !== mySeq) {
                    return;
                }
                var geocoder = new AMap.Geocoder();
                geocoder.getLocation(addr, function (status, result) {
                    if (cascadeGeocodeSeq !== mySeq) {
                        return;
                    }
                    if (status !== 'complete' || !result || result.info !== 'OK'
                        || !result.geocodes || !result.geocodes.length) {
                        return;
                    }
                    var loc = result.geocodes[0].location;
                    if (!loc) {
                        return;
                    }
                    var glng = loc.getLng();
                    var glat = loc.getLat();
                    setLatLng(glat, glng);
                    setGcjFlag(true);
                    syncDistrictPath();
                    moveMarkerTo(glng, glat);
                    setMapView(glat, glng, cascadeZoomForSelectId(deepestSel.id));
                });
            });
            return;
        }

        if (isOsm() && cfg().geocodeUrl) {
            var url = cfg().geocodeUrl + '?address=' + encodeURIComponent(addr);
            fetch(url, { credentials: 'same-origin' })
                .then(function (r) {
                    if (!r.ok) {
                        return null;
                    }
                    return r.json();
                })
                .then(function (data) {
                    if (cascadeGeocodeSeq !== mySeq || !data) {
                        return;
                    }
                    var lat = Number(data.lat);
                    var lng = Number(data.lng);
                    if (isNaN(lat) || isNaN(lng)) {
                        return;
                    }
                    if (data.gcj02 && window.WlwCoord && !isOsmGcjMap()) {
                        var w = window.WlwCoord.gcj02ToWgs84(lng, lat);
                        lng = w.lng;
                        lat = w.lat;
                    }
                    setLatLng(lat, lng);
                    setGcjFlag(isOsmGcjMap() || !!data.gcj02);
                    syncDistrictPath();
                    moveMarkerTo(lng, lat);
                    setMapView(lat, lng, cascadeZoomForSelectId(deepestSel.id));
                })
                .catch(function () { /* ignore */ });
        }
    }

    function moveMarkerTo(lng, lat) {
        if (!map) {
            return;
        }
        if (isOsm() && typeof L !== 'undefined') {
            if (!marker) {
                marker = L.marker([lat, lng]).addTo(map);
            } else {
                marker.setLatLng([lat, lng]);
            }
            map.panTo([lat, lng]);
            return;
        }
        if (isAmap() && typeof AMap !== 'undefined') {
            var ll = new AMap.LngLat(lng, lat);
            if (!marker) {
                marker = new AMap.Marker({ position: ll, map: map });
            } else {
                marker.setPosition(ll);
            }
            map.setCenter(ll);
        }
    }

    function removeMarker() {
        if (!marker || !map) {
            marker = null;
            return;
        }
        if (isOsm() && typeof marker.remove === 'function') {
            map.removeLayer(marker);
        } else if (isAmap() && typeof marker.setMap === 'function') {
            marker.setMap(null);
        }
        marker = null;
    }

    function fillSelect(sel, items, placeholder) {
        if (!sel) {
            return;
        }
        sel.innerHTML = '';
        var ph = document.createElement('option');
        ph.value = '';
        ph.textContent = placeholder;
        sel.appendChild(ph);
        for (var i = 0; i < items.length; i++) {
            var it = items[i];
            var op = document.createElement('option');
            op.value = it.adcode;
            op.textContent = it.name;
            if (it.centerLng != null && it.centerLat != null
                && !isNaN(Number(it.centerLng)) && !isNaN(Number(it.centerLat))) {
                op.setAttribute('data-lng', String(it.centerLng));
                op.setAttribute('data-lat', String(it.centerLat));
            }
            sel.appendChild(op);
        }
    }

    function fetchChildren(parent, cb) {
        if (!cfg().hasDistrictApi) {
            cb([]);
            return;
        }
        var url = cfg().districtUrl + (parent ? ('?parent=' + encodeURIComponent(parent)) : '');
        fetch(url, { credentials: 'same-origin' })
            .then(function (r) {
                return r.json();
            })
            .then(function (data) {
                cb(Array.isArray(data) ? data : []);
            })
            .catch(function () {
                cb([]);
            });
    }

    function resetCityDistrict() {
        var city = qs('dlCity');
        var dist = qs('dlDist');
        if (city) {
            fillSelect(city, [], '—');
        }
        if (dist) {
            fillSelect(dist, [], '—');
        }
    }

    function wireCascade() {
        var prov = qs('dlProv');
        var city = qs('dlCity');
        var dist = qs('dlDist');
        if (!prov || !cfg().hasDistrictApi) {
            if (prov) {
                prov.disabled = true;
            }
            if (city) {
                city.disabled = true;
            }
            if (dist) {
                dist.disabled = true;
            }
            return;
        }
        prov.addEventListener('change', function () {
            resetCityDistrict();
            if (!prov.value) {
                syncDistrictPath();
                return;
            }
            fetchChildren(prov.value, function (items) {
                fillSelect(city, items, '— 请选择 —');
                applyCenterFromCascade();
            });
        });
        city.addEventListener('change', function () {
            if (dist) {
                fillSelect(dist, [], '—');
            }
            if (!city.value) {
                syncDistrictPath();
                applyCenterFromCascade();
                return;
            }
            fetchChildren(city.value, function (items) {
                fillSelect(dist, items, '— 可选 —');
                applyCenterFromCascade();
            });
        });
        dist.addEventListener('change', function () {
            applyCenterFromCascade();
        });
    }

    function wireManualInputs() {
        var la = qs('dlLat');
        var ln = qs('dlLng');
        function onType() {
            setGcjFlag(false);
        }
        if (la) {
            la.addEventListener('input', onType);
        }
        if (ln) {
            ln.addEventListener('input', onType);
        }
    }

    function wireClear() {
        var btn = qs('dlClearLoc');
        if (!btn) {
            return;
        }
        btn.addEventListener('click', function () {
            var prov = qs('dlProv');
            var city = qs('dlCity');
            var dist = qs('dlDist');
            if (prov) {
                prov.selectedIndex = 0;
            }
            resetCityDistrict();
            setLatLng('', '');
            setGcjFlag(false);
            syncDistrictPath();
            var h = qs('dlDistrictPath');
            if (h) {
                h.value = '';
            }
            removeMarker();
        });
    }

    function loadProvinces() {
        var prov = qs('dlProv');
        if (!prov || !cfg().hasDistrictApi) {
            return;
        }
        fetchChildren(null, function (items) {
            fillSelect(prov, items, '— 请选择 —');
        });
    }

    function applyInitialFromServer() {
        var c = cfg();
        if (c.initLat == null || c.initLng == null) {
            return;
        }
        var lat = Number(c.initLat);
        var lng = Number(c.initLng);
        if (isNaN(lat) || isNaN(lng)) {
            return;
        }
        setLatLng(lat, lng);
        setGcjFlag(!!c.initGcj);
    }

    function applyInitialMarker() {
        var c = cfg();
        if (c.initLat == null || c.initLng == null || !map) {
            return;
        }
        var lat = Number(c.initLat);
        var lng = Number(c.initLng);
        if (isNaN(lat) || isNaN(lng)) {
            return;
        }
        var mc = mapCoordsFromStored(lng, lat, !!c.initGcj);
        if (mc.needAmapConvert) {
            AMap.convertFrom([[lng, lat]], 'gps', function (status, result) {
                if (result && result.info === 'ok' && result.locations && result.locations[0]) {
                    var loc = result.locations[0];
                    moveMarkerTo(loc.getLng(), loc.getLat());
                    setMapView(loc.getLat(), loc.getLng(), 13);
                } else {
                    moveMarkerTo(lng, lat);
                    setMapView(lat, lng, 13);
                }
            });
            return;
        }
        moveMarkerTo(mc.lng, mc.lat);
        setMapView(mc.lat, mc.lng, 13);
    }

    function initAmapMap() {
        if (!qs('deviceLocationMap') || typeof AMap === 'undefined') {
            return;
        }
        map = new AMap.Map('deviceLocationMap', {
            zoom: 11,
            center: [116.397428, 39.90923],
            viewMode: '2D'
        });
        map.on('click', function (ev) {
            var ll = ev.lnglat;
            setLatLng(ll.getLat(), ll.getLng());
            setGcjFlag(true);
            syncDistrictPath();
            moveMarkerTo(ll.getLng(), ll.getLat());
        });
    }

    function initOsmMap() {
        if (!qs('deviceLocationMap') || typeof L === 'undefined' || !window.WlwMapOsm) {
            return;
        }
        map = window.WlwMapOsm.createBaseMap('deviceLocationMap');
        map.setView([39.90923, 116.397428], 11);
        map.on('click', function (ev) {
            setLatLng(ev.latlng.lat, ev.latlng.lng);
            setGcjFlag(isOsmGcjMap());
            syncDistrictPath();
            moveMarkerTo(ev.latlng.lng, ev.latlng.lat);
        });
    }

    function initMap() {
        if (map) {
            return;
        }
        if (!cfg().hasMap) {
            return;
        }
        if (isAmap()) {
            initAmapMap();
        } else if (isOsm()) {
            initOsmMap();
        } else {
            return;
        }

        if (pendingCascadeGeocode) {
            pendingCascadeGeocode = false;
            setTimeout(applyCenterFromCascade, 0);
        }
        applyInitialMarker();

        if (cfg().resizeOnModal) {
            var openBtn = document.getElementById('openAddDeviceModal');
            if (openBtn) {
                openBtn.addEventListener('click', function () {
                    setTimeout(function () {
                        if (map && isAmap() && typeof map.resize === 'function') {
                            map.resize();
                        } else if (map && isOsm() && typeof map.invalidateSize === 'function') {
                            map.invalidateSize();
                        }
                    }, 280);
                });
            }
        }
    }

    window.WlwDeviceLocationPicker = {
        boot: function () {
            wireCascade();
            wireManualInputs();
            wireClear();
            loadProvinces();
            applyInitialFromServer();
        },
        onMapReady: function () {
            initMap();
        },
        onAmapReady: function () {
            initMap();
        }
    };
})();
