package com.iot.platform.protocol.modbus;

import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.net.AbstractSerialConnection;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import com.iot.platform.config.IotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Modbus RTU 经串口（常见为 RS485 收发器）。需在 application.yml 配置端口名与波特率等。
 */
@Component
@RequiredArgsConstructor
public class ModbusRtuAdapter {

    private final IotProperties iotProperties;

    public Map<String, Object> readHoldingRegisters(int ref, int count) throws Exception {
        if (!iotProperties.getModbusRtu().isEnabled()) {
            throw new IllegalStateException("Modbus RTU/RS485 未启用，请设置 iot.modbus-rtu.enabled=true 并配置串口");
        }
        SerialParameters sp = new SerialParameters();
        sp.setPortName(iotProperties.getModbusRtu().getPortName());
        sp.setBaudRate(iotProperties.getModbusRtu().getBaudRate());
        sp.setDatabits(iotProperties.getModbusRtu().getDataBits());
        sp.setStopbits(mapStopBits(iotProperties.getModbusRtu().getStopBits()));
        sp.setParity(mapParity(iotProperties.getModbusRtu().getParity()));
        sp.setEncoding(com.ghgande.j2mod.modbus.Modbus.SERIAL_ENCODING_RTU);
        sp.setRs485Mode(true);

        ModbusSerialMaster master = new ModbusSerialMaster(sp);
        int unitId = iotProperties.getModbusRtu().getUnitId();
        try {
            master.connect();
            Register[] regs = master.readMultipleRegisters(unitId, ref, count);
            List<Integer> values = Arrays.stream(regs).map(Register::getValue).collect(Collectors.toList());
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("port", sp.getPortName());
            out.put("unitId", unitId);
            out.put("reference", ref);
            out.put("function", "readHoldingRegisters RTU (0x03)");
            out.put("values", values);
            return out;
        } finally {
            master.disconnect();
        }
    }

    private int mapStopBits(int n) {
        if (n == 2) {
            return AbstractSerialConnection.TWO_STOP_BITS;
        }
        return AbstractSerialConnection.ONE_STOP_BIT;
    }

    private int mapParity(String p) {
        if (p == null) {
            return AbstractSerialConnection.NO_PARITY;
        }
        String u = p.trim().toUpperCase();
        if ("EVEN".equals(u)) {
            return AbstractSerialConnection.EVEN_PARITY;
        }
        if ("ODD".equals(u)) {
            return AbstractSerialConnection.ODD_PARITY;
        }
        return AbstractSerialConnection.NO_PARITY;
    }
}
