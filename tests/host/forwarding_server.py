"""Isolated SSH forwarding peer. Never authenticates a system account or opens a shell."""
import base64
import pathlib
import socket
import sys
import threading
import paramiko

host_key, client_key, port_file = sys.argv[1:]
authorized = paramiko.RSAKey(data=base64.b64decode(pathlib.Path(client_key).read_text().split()[1]))

def bridge(a, b):
    def copy(src, dst):
        try:
            while data := src.recv(32768):
                dst.sendall(data)
        except (OSError, EOFError):
            pass
        finally:
            try:
                if isinstance(dst, paramiko.Channel):
                    dst.shutdown_write()
                else:
                    dst.shutdown(socket.SHUT_WR)
            except OSError:
                pass
    one = threading.Thread(target=copy, args=(a, b), daemon=True)
    two = threading.Thread(target=copy, args=(b, a), daemon=True)
    one.start(); two.start(); one.join(); two.join()
    a.close(); b.close()

class Server(paramiko.ServerInterface):
    def __init__(self, transport):
        self.transport = transport
        self.targets = {}
        self.listeners = {}
    def get_allowed_auths(self, user):
        return "publickey"
    def check_auth_publickey(self, user, key):
        return paramiko.AUTH_SUCCESSFUL if user == "test-user" and key == authorized else paramiko.AUTH_FAILED
    def check_channel_direct_tcpip_request(self, channel_id, origin, destination):
        if destination[0] != "127.0.0.1":
            return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        self.targets[channel_id] = destination
        return paramiko.OPEN_SUCCEEDED
    def check_port_forward_request(self, address, port):
        if address != "127.0.0.1":
            return False
        listener = socket.socket()
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((address, port)); listener.listen(8); listener.settimeout(.2)
        self.listeners[port] = listener
        def accept():
            while self.transport.is_active():
                try:
                    connection, origin = listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    return
                channel = self.transport.open_forwarded_tcpip_channel(origin, (address, port))
                threading.Thread(target=bridge, args=(connection, channel), daemon=True).start()
        threading.Thread(target=accept, daemon=True).start()
        return port
    def cancel_port_forward_request(self, address, port):
        listener = self.listeners.pop(port, None)
        if listener:
            listener.close()

def serve(connection):
    with paramiko.Transport(connection) as transport:
        transport.add_server_key(paramiko.RSAKey.from_private_key_file(host_key))
        server = Server(transport)
        try:
            transport.start_server(server=server)
            while transport.is_active():
                channel = transport.accept(.2)
                if channel is None:
                    continue
                destination = server.targets.pop(channel.get_id(), None)
                if destination:
                    target = socket.create_connection(destination, timeout=5)
                    target.settimeout(None)
                    threading.Thread(target=bridge, args=(channel, target), daemon=True).start()
        finally:
            for listener in server.listeners.values():
                listener.close()

with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0)); listener.listen(8)
    pathlib.Path(port_file).write_text(str(listener.getsockname()[1]))
    while True:
        connection, _ = listener.accept()
        threading.Thread(target=serve, args=(connection,), daemon=True).start()
