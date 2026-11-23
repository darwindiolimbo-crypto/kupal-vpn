# SSH UDP gateway setup

Kupal VPN's SSH engine forwards TCP streams through SSH `direct-tcpip` channels. For UDP traffic (including DNS when the device resolver uses UDP), the SSH server must also run a BadVPN UDP gateway listening on loopback. The Android client connects to that service through an SSH-forwarded channel; do **not** expose the UDPGW port to the public internet.

## Requirements

- A Linux SSH server that you administer.
- SSH port forwarding enabled for the account (the client opens `127.0.0.1:7300` from the SSH server).
- `badvpn-udpgw` built from the upstream BadVPN project.

## Build and install

On an Ubuntu/Debian server:

```sh
sudo apt-get update
sudo apt-get install -y build-essential cmake git
git clone --depth 1 https://github.com/ambrop72/badvpn.git
cmake -S badvpn -B badvpn/build \
  -DBUILD_NOTHING_BY_DEFAULT=1 \
  -DBUILD_UDPGW=1
cmake --build badvpn/build --target badvpn-udpgw --parallel
sudo install -m 0755 badvpn/build/udpgw/badvpn-udpgw /usr/local/sbin/badvpn-udpgw
```

Create `/etc/systemd/system/kupal-udpgw.service`:

```ini
[Unit]
Description=Kupal VPN SSH UDP gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/sbin/badvpn-udpgw --loglevel warning --listen-addr 127.0.0.1:7300
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Enable it:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now kupal-udpgw
sudo systemctl status kupal-udpgw
```

Check it is listening only on localhost:

```sh
sudo ss -lntp | grep ':7300'
```

Do not add a public firewall allow rule for port 7300. The Android app reaches it over the encrypted SSH forwarding channel.

## Configure Kupal VPN

In **Config → SSH → UDP relay**, set the server-side UDPGW port to `7300`. Set it to `0` to disable UDP relay (TCP-only operation). The SSH account must be allowed to forward to `127.0.0.1:7300`.

## Notes

- This daemon is separate from the SSH server. A normal SSH login by itself does not provide UDP forwarding.
- If the daemon is missing, blocked, or incompatible, TCP SSH connections may still work, but UDP-based traffic can fail.
- Use only a server you administer or are authorized to use.
- Upstream project and command-line options: https://github.com/ambrop72/badvpn
