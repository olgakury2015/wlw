(function () {
    'use strict';

    function qs(id) {
        return document.getElementById(id);
    }

    function syncBlocks() {
        var uplink = qs('gwUplinkSelect');
        var remote = qs('gwRemoteBlock');
        var mqtt = qs('gwMqttBlock');
        if (!uplink) {
            return;
        }
        var v = uplink.value;
        if (remote) {
            remote.style.display = (v === 'TCP_SERVER' || v === 'MODBUS_TCP') ? 'block' : 'none';
        }
        if (mqtt) {
            mqtt.style.display = (v === 'MQTT') ? 'block' : 'none';
        }
    }

    window.WlwGatewayForm = {
        boot: function () {
            var uplink = qs('gwUplinkSelect');
            if (uplink) {
                uplink.addEventListener('change', syncBlocks);
                syncBlocks();
            }
        }
    };
})();
