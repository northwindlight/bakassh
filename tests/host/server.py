"""Synthetic SSH peer with terminal queries; no system login or real shell."""
import base64
import pathlib
import socket
import sys
import threading

import paramiko

host_key, client_key, port_file = sys.argv[1:]
authorized = paramiko.RSAKey(data=base64.b64decode(pathlib.Path(client_key).read_text().split()[1]))


class TerminalServer(paramiko.ServerInterface):
    def __init__(self):
        self.shell = threading.Event()
        self.size = (80, 24)

    def check_auth_publickey(self, username, key):
        return paramiko.AUTH_SUCCESSFUL if key == authorized else paramiko.AUTH_FAILED

    def get_allowed_auths(self, username):
        return "publickey"

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_pty_request(self, channel, term, width, height, pixelwidth, pixelheight, modes):
        self.size = (width, height)
        return True

    def check_channel_window_change_request(self, channel, width, height, pixelwidth, pixelheight):
        self.size = (width, height)
        return True

    def check_channel_shell_request(self, channel):
        self.shell.set()
        return True


def serve(connection):
    with paramiko.Transport(connection) as transport:
        transport.add_server_key(paramiko.RSAKey.from_private_key_file(host_key))
        server = TerminalServer()
        transport.start_server(server=server)
        channel = transport.accept(5)
        assert channel is not None and server.shell.wait(5)
        with channel:
            channel.settimeout(5)
            channel.sendall(b"\x1b[>0q")
            reply = bytearray()
            while not reply.endswith(b"\x1b\\"):
                data = channel.recv(1)
                if not data:
                    raise RuntimeError("EOF before version reply")
                reply.extend(data)
            assert reply == b"\x1bP>|libvterm(0.3)\x1b\\", reply
            channel.sendall(b"VERSION_OK\r\n")
            line = bytearray()
            while True:
                data = channel.recv(1)
                if not data:
                    break
                if data != b"\n":
                    line.extend(data)
                    continue
                if line == b"ping":
                    channel.sendall(b"PONG\r\n")
                elif line == b"size":
                    channel.sendall(f"SIZE {server.size[0]} {server.size[1]}\r\n".encode())
                elif line.startswith(b"tag "):
                    channel.sendall(line[4:] + b"\r\n")
                elif line == b"exit":
                    channel.send_exit_status(0)
                    break
                line.clear()


with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)
    pathlib.Path(port_file).write_text(str(listener.getsockname()[1]))
    while True:
        connection, _ = listener.accept()
        threading.Thread(target=serve, args=(connection,), daemon=True).start()
