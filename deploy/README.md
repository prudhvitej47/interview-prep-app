# Deploying

The app runs on one Lightsail VM in Mumbai, reachable only over Tailscale at
`https://interview-prep.tail82d7a5.ts.net`. Nothing reaches the VM from outside: it pulls.

## How a commit becomes a running app

1. A pull request is checked by `build.yml`; nothing it does can publish.
2. Landing on `main` runs `publish.yml`, which runs the same checks again, then builds the image
   for `linux/amd64` and pushes it to ECR twice: as the commit sha, and as `main`.
3. A timer on the VM runs `update.sh` every two minutes. It pulls `…/interview-prep-app:main` and
   restarts the app only if that image actually changed.

So a merge is live within about three minutes, with no step on anyone's machine.

## What runs on the VM

| Path | What it is |
| --- | --- |
| `/opt/interview-prep/src` | A shallow clone of this repository's `main`, which is where the running Compose file and units come from. |
| `/etc/interview-prep/backup.env` | Written at first boot by Terraform. The VM's AWS key, used for backups and now also to pull images. Never print it. |
| `/etc/interview-prep/app.env` | The image reference and the database password, generated once by `vm-setup.sh`. |
| `/data/postgres` | The database, on its own 8 GB disk. Nothing here ever deletes it. |

Two containers: `app` (768 MB) and `postgres` (512 MB), on a machine with 1.9 GB of RAM and 2 GB
of swap. PostgreSQL publishes no ports and is reachable only on the Compose network; the app is
bound to `127.0.0.1:8080` and reached only through `tailscale serve`.

## Setting up, or re-syncing, the VM

From the Mac. Nothing needs to be copied to the server first, and no credential is passed:

```bash
ECR_REGISTRY=<account-id>.dkr.ecr.ap-south-1.amazonaws.com
tailscale ssh ubuntu@interview-prep "sudo ECR_REGISTRY=$ECR_REGISTRY bash -s" < deploy/vm-setup.sh
```

It is safe to re-run: it keeps the existing database password, updates the clone, reinstalls the
units and re-applies the Tailscale configuration. Run it after changing anything in this folder.

A rebuilt VM needs this one command and nothing else, which is the point of keeping all of it here
rather than on the server.

## If the VM is ever replaced

A **reboot** needs nothing. Everything below is persistent state — an installed package, a config
file, systemd symlinks, a clone on disk, and the Tailscale serve configuration in tailscaled's own
state — so systemd brings the app back by itself.

A **replacement** is different. `terraform apply` with `replace_vm` destroys the instance and
creates a new one, and the first-boot script does not know anything about the application. Two
manual steps are required, in this order:

1. **A fresh Tailscale auth key**, in the infra repository's `TAILSCALE_AUTH_KEY` secret, *before*
   the apply. The original key was single-use and is spent, so without this the new VM never joins
   the tailnet and nothing can reach it — including you.
2. **Run `vm-setup.sh` again** once the new VM is up, exactly as above. The data disk is re-attached
   rather than reformatted, so the database survives; this only reinstalls the parts that live on
   the instance's own root disk.

The infra repository's `docs/runbook.md` has the replacement procedure itself. This is the part that
belongs to the application, which is why it is written down here as well.

Automating step 2 — having the first-boot script fetch and run this one — is worth doing at some
point. Until then, replacing the VM is a supervised operation, which step 1 makes it anyway.

## Looking at it

```bash
tailscale ssh ubuntu@interview-prep
systemctl status interview-prep-update.timer            # when the next check runs
journalctl -u interview-prep-update.service -n 50       # what the last checks did
sudo docker compose -p interview-prep \
  -f /opt/interview-prep/src/deploy/docker-compose.yml logs -f app
```

## Confirmed on the VM, 2026-09-20

The first run of `vm-setup.sh` pulled both images, brought the stack up and served the app over
HTTPS. Worth recording because two of these were assumptions until then:

- The VM pulled from ECR with the `backup-writer` key already on it, through
  `amazon-ecr-credential-helper`. No AWS CLI, no `docker login`.
- The update timer ran, pulled, found the image unchanged and left the container alone —
  `Container interview-prep-app Running`, no restart.
- `tailscale serve` obtained a Let's Encrypt certificate for the tailnet name and terminates TLS.
- Memory sits at about 245 MB for the app and 59 MB for PostgreSQL, with 1.1 GB free and no swap
  in use.

## Deliberate choices

**No digest comparison in `update.sh`.** `docker compose pull` does nothing when the image is
unchanged, and `docker compose up -d` recreates a container only when its image id differs. Both
halves were verified before relying on them. Writing the comparison by hand would be more code for
the same result, and getting it backwards means either never updating or restarting every two
minutes.

**`tailscale serve` rather than a reverse proxy.** `tailscaled` already holds the node's identity
and renews its certificate, so it terminates TLS and forwards to the app. No second container, no
certificate timer, no memory spent on a box that has little to spare.

**The VM pulls with the backup key.** Lightsail instances cannot assume an IAM role, and the
first-boot script only runs once, so a new key would have to be carried to a running server by
hand. The key written at first boot is already there, and its ECR permissions are read-only and
limited to this project's two repositories. `amazon-ecr-credential-helper` exchanges it for a
registry token on demand, so there is no `docker login` to keep fresh and no AWS CLI on the VM.

**Never `docker compose down`, and never `-v`.** The database is a bind mount on the dedicated
disk. The units stop containers; they do not remove them or their volumes.
