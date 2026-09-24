#!/usr/bin/env python3
"""Raspberry Pi sensor server.

DHT22  -> room temperature + humidity (GPIO4, physical pin 7)
MLX90614 -> person (object) temperature over I2C (SDA=GPIO2, SCL=GPIO3)

Serves JSON at http://<pi-ip>:5000/data

Install:
    sudo apt install -y python3-pip libgpiod2
    pip3 install --break-system-packages adafruit-circuitpython-dht \
        adafruit-circuitpython-mlx90614 adafruit-blinka
    sudo raspi-config nonint do_i2c 0      # enable I2C
Run:
    python3 server.py
"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import board
import busio
import adafruit_dht
import adafruit_mlx90614

PORT = 5000
DHT_PIN = board.D4  # change if your DHT22 data pin is elsewhere

state = {
    "room_temp": None,
    "humidity": None,
    "user_temp": None,
    "mlx_ambient": None,
    "timestamp": None,
    "errors": {},
}
lock = threading.Lock()


def sensor_loop():
    dht = adafruit_dht.DHT22(DHT_PIN)
    mlx = None
    try:
        mlx = adafruit_mlx90614.MLX90614(busio.I2C(board.SCL, board.SDA, frequency=100000))
    except Exception as e:  # noqa: BLE001
        with lock:
            state["errors"]["mlx"] = str(e)

    while True:
        # DHT22 is flaky; a failed read is normal, just keep the last good value.
        try:
            t, h = dht.temperature, dht.humidity
            if t is not None and h is not None:
                with lock:
                    state["room_temp"] = round(t, 1)
                    state["humidity"] = round(h, 1)
                    state["errors"].pop("dht", None)
        except Exception as e:  # noqa: BLE001
            with lock:
                state["errors"]["dht"] = str(e)

        if mlx is None:
            try:
                mlx = adafruit_mlx90614.MLX90614(busio.I2C(board.SCL, board.SDA, frequency=100000))
            except Exception:  # noqa: BLE001
                pass
        if mlx is not None:
            try:
                obj = mlx.object_temperature
                amb = mlx.ambient_temperature
                with lock:
                    state["user_temp"] = round(obj, 1)
                    state["mlx_ambient"] = round(amb, 1)
                    state["errors"].pop("mlx", None)
            except Exception as e:  # noqa: BLE001
                with lock:
                    state["errors"]["mlx"] = str(e)

        with lock:
            state["timestamp"] = time.strftime("%H:%M:%S")
        time.sleep(2.5)  # DHT22 needs >= 2s between reads


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] not in ("/", "/data"):
            self.send_error(404)
            return
        with lock:
            body = json.dumps(state).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    threading.Thread(target=sensor_loop, daemon=True).start()
    print(f"Serving on port {PORT}  ->  http://<pi-ip>:{PORT}/data")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
