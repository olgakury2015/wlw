package com.iot.platform.protocol.modbus;

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.iot.platform.config.IotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Modbus TCP（以太网侧）。RS485 物理层上的 Modbus RTU 见 {@link ModbusRtuAdapter}。
 */
@Component
@RequiredArgsConstructor
public class ModbusTcpAdapter {

    private final IotProperties iotProperties;

    public Map<String, Object> readHoldingRegisters(String host, Integer port, int unitId, int ref, int count)
            throws Exception {
        int p = port != null ? port : iotProperties.getModbusTcp().getDefaultPort();
        String h = host != null ? host : iotProperties.getModbusTcp().getDefaultHost();
        ModbusTCPMaster master = new ModbusTCPMaster(h, p);
        try {
            master.connect();
            Register[] regs = master.readMultipleRegisters(unitId, ref, count);
            List<Integer> values = Arrays.stream(regs).map(Register::getValue).collect(Collectors.toList());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", h);
            out.put("port", p);
            out.put("unitId", unitId);
            out.put("reference", ref);
            out.put("function", "readHoldingRegisters (0x03)");
            out.put("values", values);
            return out;
        } finally {
            master.disconnect();
        }
    }

    public Map<String, Object> readInputRegisters(String host, Integer port, int unitId, int ref, int count)
            throws Exception {
        int p = port != null ? port : iotProperties.getModbusTcp().getDefaultPort();
        String h = host != null ? host : iotProperties.getModbusTcp().getDefaultHost();
        ModbusTCPMaster master = new ModbusTCPMaster(h, p);
        try {
            master.connect();
            InputRegister[] regs = master.readInputRegisters(unitId, ref, count);
            List<Integer> values = Arrays.stream(regs).map(InputRegister::getValue).collect(Collectors.toList());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", h);
            out.put("port", p);
            out.put("unitId", unitId);
            out.put("reference", ref);
            out.put("function", "readInputRegisters (0x04)");
            out.put("values", values);
            return out;
        } finally {
            master.disconnect();
        }
    }

    public Map<String, Object> readCoils(String host, Integer port, int unitId, int ref, int count)
            throws Exception {
        int p = port != null ? port : iotProperties.getModbusTcp().getDefaultPort();
        String h = host != null ? host : iotProperties.getModbusTcp().getDefaultHost();
        ModbusTCPMaster master = new ModbusTCPMaster(h, p);
        try {
            master.connect();
            BitVector bits = master.readCoils(unitId, ref, count);
            boolean[] arr = new boolean[bits.size()];
            for (int i = 0; i < bits.size(); i++) {
                arr[i] = bits.getBit(i);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("host", h);
            out.put("port", p);
            out.put("unitId", unitId);
            out.put("reference", ref);
            out.put("function", "readCoils (0x01)");
            out.put("bits", arr);
            return out;
        } finally {
            master.disconnect();
        }
    }
}
