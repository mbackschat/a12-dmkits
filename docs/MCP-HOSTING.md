# Hosting the MCP endpoint — the runbook

How to stand up the public `dmtool mcp --http` endpoint on **alwaysdata's free tier**, and what to do when a deploy fails. The *contract* it serves (the wire, the status mapping, the concurrency posture) is [MCP-SPEC](MCP-SPEC.md) §7; the *candidate assessment* that picked this host, and the requirements a host must meet, are [MCP-GAPS](MCP-GAPS.md) "Hosting". This file is only the procedure.

It is not a one-time procedure. A free account may be deleted at the vendor's discretion (H11), so "rebuild it from nothing" is a scenario, not a hypothetical.

## The shape

Three parties, and nothing in the middle holds state:

```
your Mac                GitHub Actions                   alwaysdata
  ssh-keygen  ──pub──────────────────────────────────▶  authorized key
              ──priv──▶  secret MCP_DEPLOY_SSH_KEY
                         deploy-mcp-endpoint.yml
                           downloads the RELEASED
                           dmtool-linux-x64  ──scp──▶  ~/bin/dmtool
                           restart via API   ──POST─▶  site restarts
                           smoke test        ──POST─▶  /mcp answers
```

The pipeline ships the **released** binary rather than building one, verified against `SHA256SUMS`, so production runs the byte-identical artifact a user downloads. Cut a release first; the deploy is a separate, manual step.

## 1. The alwaysdata site (once, in the admin UI)

Create a site of type **user program**. That type is the fit because the platform assigns the address and the program binds it, which is exactly what this server does.

**Command:**

```
/home/<account>/bin/dmtool -Xmx192m mcp --http
```

Three things about that line, each load-bearing:

- **No `--host`/`--port`.** They default to the platform's `IP` and `PORT` variables (MCP-SPEC §7: the environment may say where to listen, never what an answer means). For user-program sites the assigned `IP` may be **IPv6**; the default handles it. The older `ALWAYSDATA_HTTPD_*` variables no longer exist.
- **The `-Xmx` pin is not optional.** A native image sizes its heap from *physical* host RAM, so an unpinned process on a 256 MB plan is killed, which takes the endpoint down for everyone rather than failing one request.
- **No `--allow-origin`.** Nothing is allowlisted, so a browser origin gets `403`. Add origins only when a browser client actually needs one.

Note down the site's **numeric id** (it is in the admin URL) and the account name; the pipeline restarts through the API with both.

## 2. The credentials (once, from your Mac)

A keypair splits across the two ends of the connection, and your machine is a third party to that. Generate locally: alwaysdata would have to send the private half back to you, and a GitHub runner is destroyed after each job.

| half | goes to | role |
|---|---|---|
| private `~/.ssh/alwaysdata_ci` | GitHub secret `MCP_DEPLOY_SSH_KEY` | the runner is the **client** |
| public `~/.ssh/alwaysdata_ci.pub` | alwaysdata admin ▸ Remote access ▸ SSH keys | the host is the **server** |

```bash
ssh-keygen -t ed25519 -f ~/.ssh/alwaysdata_ci -C "gh-actions deploy-mcp-endpoint" -N ""
pbcopy < ~/.ssh/alwaysdata_ci.pub     # paste into the alwaysdata admin
```

No passphrase: an unattended `scp` cannot answer a prompt. A **dedicated** key rather than your own is what bounds the blast radius, since it is revocable on its own without locking you out. Keep the local private half afterwards; it is what lets you `ssh -i ~/.ssh/alwaysdata_ci …` by hand when the pipeline fails, which is precisely when you want it.

Then set the repository's configuration. Run these in a terminal, not through an agent, so no value lands in a transcript:

```bash
R=mbackschat/a12-dmkits
# role-named: these mean the same thing on any SSH-reachable host
gh secret   set MCP_DEPLOY_SSH_KEY   --repo $R < ~/.ssh/alwaysdata_ci
gh variable set MCP_DEPLOY_SSH_HOST  --repo $R     # ssh-<account>.alwaysdata.net
gh variable set MCP_DEPLOY_SSH_USER  --repo $R
gh variable set MCP_DEPLOY_BIN_PATH  --repo $R     # /home/<account>/bin/dmtool
gh variable set MCP_ENDPOINT_URL     --repo $R     # https://<site>/mcp
# vendor-named: only the `activate` step reads these
gh secret   set ALWAYSDATA_API_KEY   --repo $R     # prompts; token from the account's Profile section
gh variable set ALWAYSDATA_SITE_ID   --repo $R     # the numeric id from step 1
gh variable set ALWAYSDATA_ACCOUNT   --repo $R
```

The split is deliberate: five settings describe a **role** and survive a change of host, three name alwaysdata and do not. See "Porting to another host" below.

**Secrets are masked in run logs; variables are not.** The host, account and URL will appear in any log anyone reads. That is acceptable for a demonstration endpoint and worth knowing before you paste a log somewhere.

The pipeline **fails fast** on a missing one rather than uploading and then discovering it cannot restart, because a half-configured deploy leaves the old process serving while the log reads green.

## 3. Deploying

```bash
gh workflow run deploy-mcp-endpoint --repo mbackschat/a12-dmkits          # the latest release
gh workflow run deploy-mcp-endpoint --repo mbackschat/a12-dmkits -f tag=<release-tag>
gh workflow run deploy-mcp-endpoint --repo mbackschat/a12-dmkits -f smoke_only=true
```

Deploying is **manual only**, and one at a time (a concurrency group, because a second deploy would race the restart). It uploads to `<bin>.incoming` and *moves* it into place, so a partially transferred binary is never the one the platform restarts into.

**It keeps the binary it replaces, and rolls back by itself.** The smoke test necessarily runs *after* the restart, so by the time a bad build is known to be bad it is already serving. The outgoing binary is therefore kept as `<bin>.previous`; if the smoke test fails, the job restores it, restarts again, and still reports failure. A *first* deploy has nothing to restore and says so.

**A read-only heartbeat runs daily** (`06:17 UTC`, offset from the nightly performance run), which is the same workflow with `smoke_only`. It exists because this endpoint is linked from the public README while running on a plan whose account the vendor may delete and whose process can be OOM-killed: without it, a dead endpoint is discovered by a user. Before `MCP_ENDPOINT_URL` is configured the scheduled run **skips** rather than failing, so it does not sit red before there is anything to check.

Then it proves the endpoint with [`scripts/smoke-mcp-endpoint.sh`](../scripts/smoke-mcp-endpoint.sh), which is read-only and drives the real protocol: `server/discover` advertises the revision and names this server, `tools/list` carries the derived surface, a `tools/call` sends a model inline and gets an envelope back, and a deliberately mismatched header still gets its `400`. Bounded retries, so a permanently dead endpoint fails the job instead of hanging it. Run it against localhost too:

```bash
dmtool mcp --http --port 8080 &
./scripts/smoke-mcp-endpoint.sh http://127.0.0.1:8080/mcp
```

## 4. When it fails

| symptom | the likely cause |
|---|---|
| `missing repository configuration: …` | step 2 is incomplete; the names are listed in the workflow header |
| `Permission denied (publickey)` | the **public** half never reached the admin UI, or the key has a passphrase |
| the restart returns `401` | the basic-auth user is `"$API_KEY account=$ACCOUNT:"`, trailing colon included |
| the restart succeeds, the smoke test never passes | read the site's own log in the admin UI. A missing `-Xmx` pin shows up as a process that starts and dies |
| `server/discover` answers but `tools/call` does not | a memory ceiling reached during a real call. Authoring is cheap; evaluation is not (below) |
| everything green, the endpoint 404s from outside | the site's URL differs from `MCP_ENDPOINT_URL`; the smoke test proved a *different* endpoint |
| `no <bin>.previous on the host` | the smoke test failed on a **first** deploy, so there is no earlier binary to restore. Fix forward, or upload one by hand |
| the daily heartbeat turns red | the endpoint died between deploys. Check the host is still there at all (a free account can be removed), then the site log |

## 5. Porting to another host

alwaysdata is the host *for now*, not an assumption baked through the pipeline. The job runs in four phases and only one of them knows the vendor:

| phase | what it does | portable? |
|---|---|---|
| **acquire** | download the released `dmtool-linux-x64`, verify it against `SHA256SUMS` | yes, entirely |
| **place** | `scp` beside the live path, keep the outgoing binary as `.previous`, atomic `mv` | any SSH-reachable host |
| **activate** | `POST https://api.alwaysdata.com/v1/site/<id>/restart/` | **this is the vendor-specific one** |
| **prove** | [`smoke-mcp-endpoint.sh`](../scripts/smoke-mcp-endpoint.sh) against the public URL | yes — it takes a URL and nothing else |

So a move is: rewrite the **activate** step, re-point the five `MCP_DEPLOY_*`/`MCP_ENDPOINT_URL` settings, and drop the three `ALWAYSDATA_*` ones. Everything else, including the rollback, is unchanged.

**The binary is already host-portable.** `--host`/`--port` default to `$IP`/`$PORT`, and that is the convention on Cloud Run, Heroku, Fly and Railway too, not an alwaysdata quirk ([MCP-SPEC](MCP-SPEC.md) §7). Nothing else is read from the environment.

**What cannot be abstracted is the start command.** It lives in the host's own configuration, and each kind of host expresses it differently: an alwaysdata *user program* form, a systemd unit, a Dockerfile `CMD`, a Procfile. Whatever the form, it must pin the heap — see §1.

*(Not yet extracted into a per-provider script, deliberately: `place` and `activate` have not run successfully even once, and refactoring them before the first real deploy means debugging an abstraction and a credential problem at the same time. Once the concrete path is proven, lifting those two steps behind a `place`/`activate` contract is mechanical. **Re-open when** a second host actually exists.)*

## 6. What this endpoint is not

- **Not authenticated.** A specification SHOULD that this server does not implement. Nothing is stored, so there is no data to reach, but there is CPU to burn. Put it behind the platform's own auth or a private network if it is not meant to be public.
- **Not sized for document evaluation.** Authoring and inspection were measured flat at **~43 MiB**; a document evaluation reached **519 MiB** on a 714 KB / 100-row stress fixture. A 256 MB plan carries roughly 6× headroom for the first and cannot promise the second. *(Both figures were measured 2026-08-06, before the interpreter's preparation-ownership change; they are process RSS, a different quantity from the per-model resident cost in [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF253. Re-measure on the next native build.)* Nothing accumulates between requests, so per-model cost never adds up across calls: the server holds no state at all.
- **Not customer infrastructure.** The vendor may delete a free account at its discretion, so this is right where a redeploy is the whole cost. A customer who wants to run it themselves is [MG12](MCP-GAPS.md) (a container image); us hosting it as a product is [MG13](MCP-GAPS.md), and that one has a licensing consequence that cannot be undone.
- **Not yet proven in restricted mode.** An account with no payment instrument sits in *restricted mode*, whose "stricter filter rules" the vendor does not specify. An endpoint receiving automated agent traffic is plausibly what such rules exist to catch. Only a real deployment settles it.
