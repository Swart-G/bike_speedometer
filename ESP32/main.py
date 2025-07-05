import micropython
import time
import struct
from machine import Pin
from bluetooth import BLE, UUID

# --- UUID Nordic UART Service ---
_UART_SERVICE_UUID = UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_TX_UUID = UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_RX_UUID = UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

_FLAG_WRITE = 0x0008
_FLAG_NOTIFY = 0x0010

ble = BLE()
ble.active(True)

try:
    mac_bytes = ble.config('mac')
    mac_str = ':'.join('{:02X}'.format(b) for b in mac_bytes)
    print("BLE MAC address:", mac_str)
except Exception as e:
    print("Failed to get BLE MAC:", e)

adv_payload = None
resp_payload = None
_need_advertise = False

def schedule_advertise(arg):
    global _need_advertise
    _need_advertise = True

class BLEUART:
    def __init__(self, ble, name='ESP32_SPEED'):
        self._ble = ble
        self._ble.irq(self._irq)
        uart_service = (_UART_SERVICE_UUID, (
            (_UART_TX_UUID, _FLAG_NOTIFY),
            (_UART_RX_UUID, _FLAG_WRITE),
        ))
        ((self._tx_handle, self._rx_handle),) = self._ble.gatts_register_services((uart_service,))
        self._name = name
        name_payload = self._advertising_payload(name=name)
        service_payload = self._advertising_payload(services=[_UART_SERVICE_UUID])
        global adv_payload, resp_payload
        adv_payload = name_payload
        resp_payload = service_payload
        self._connections = set()
        schedule_advertise(None)

    def _irq(self, event, data):
        if event == 1:
            conn_handle, _, _ = data
            self._connections.add(conn_handle)
            print("BLE: Connected, handle =", conn_handle)
        elif event == 2:
            conn_handle, _, _ = data
            self._connections.discard(conn_handle)
            print("BLE: Disconnected, handle =", conn_handle)
            try:
                micropython.schedule(schedule_advertise, 0)
            except RuntimeError:
                pass
        elif event == 3:
            conn_handle, attr_handle = data
            if attr_handle == self._rx_handle:
                buf = self._ble.gatts_read(self._rx_handle)
                cmd = buf.decode().strip()
                print("BLE: Received on RX:", cmd)

    def send(self, data: str):
        for conn_handle in self._connections:
            try:
                self._ble.gatts_notify(conn_handle, self._tx_handle, data)
            except Exception as e:
                print("BLE: Notify error:", e)

    @staticmethod
    def _advertising_payload(limited_disc=False, br_edr=False, name=None, services=None):
        payload = bytearray()
        def _append(adv_type, value):
            payload.extend(struct.pack('BB', len(value) + 1, adv_type) + value)
        flags = (0x02 if limited_disc else 0x06)
        _append(0x01, struct.pack('B', flags))
        if name:
            _append(0x09, name.encode())
        if services:
            for uuid in services:
                b = bytes(uuid)
                if len(b) == 2:
                    _append(0x03, b)
                elif len(b) == 4:
                    _append(0x05, b)
                elif len(b) == 16:
                    _append(0x07, b)
        return payload

# --- BLE UART ---
ble_uart = BLEUART(ble, name='ESP32_SPEED')

# --- Расчёт скорости ---
micropython.alloc_emergency_exception_buf(100)
WHEEL_DIAMETER = 0.6985
WHEEL_CIRCUMFERENCE = 3.14159 * WHEEL_DIAMETER

last_time = time.ticks_ms()
last_dt = 1000  # 1 сек — начальное значение

def send_speed_callback(arg):
    speed = arg / 100

    # --- Ограничение минимальной скорости ---
    if speed < 3.0:
        speed = 0.0

    msg = "{:.2f}\n".format(speed)
    ble_uart.send(msg)
    print("Speed sent:", msg.strip())


_schedule_send = send_speed_callback

# --- Обработка сигнала с Холла ---
def hall_irq(pin):
    global last_time, last_dt
    now = time.ticks_ms()
    dt = time.ticks_diff(now, last_time)
    if dt <= 0:
        dt = 1
    last_dt = dt
    last_time = now
    speed = (WHEEL_CIRCUMFERENCE / (dt / 1000)) * 3.6
    arg = int(speed * 100)
    try:
        micropython.schedule(_schedule_send, arg)
    except RuntimeError:
        pass

hall_pin = Pin(15, Pin.IN, Pin.PULL_UP)
hall_pin.irq(trigger=Pin.IRQ_RISING, handler=hall_irq)

# --- Фоновая отправка каждые 0.5 секунды ---
def update_speed():
    global last_time, last_dt
    now = time.ticks_ms()
    dt_since = time.ticks_diff(now, last_time)
    if dt_since > last_dt:
        artificial_dt = dt_since
        speed = (WHEEL_CIRCUMFERENCE / (artificial_dt / 1000)) * 3.6
        arg = int(speed * 100)
        try:
            micropython.schedule(_schedule_send, arg)
        except RuntimeError:
            pass

# --- Основной цикл ---
TIMER_INTERVAL_MS = 500
last_update = time.ticks_ms()

while True:
    now = time.ticks_ms()

    if _need_advertise:
        time.sleep_ms(200)
        try:
            ble.gap_advertise(None)
        except OSError:
            pass
        try:
            ble.gap_advertise(500000, adv_data=adv_payload, resp_data=resp_payload)
            print("Advertising started with name:", ble_uart._name)
        except TypeError:
            try:
                ble.gap_advertise(500000, adv_data=adv_payload)
                print("Advertising started (no resp_data) with name:", ble_uart._name)
            except OSError as e:
                print("Advertising failed:", e)
        except OSError as e:
            print("Advertising failed:", e)
        _need_advertise = False

    if time.ticks_diff(now, last_update) >= TIMER_INTERVAL_MS:
        last_update = now
        update_speed()

    time.sleep_ms(50)
