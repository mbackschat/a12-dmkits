# Hosting the MCP endpoint — the runbook

How to stand up the public `dmtool mcp --http` endpoint on **alwaysdata's free tier**, and what to do when a deploy fails. The *contract* it serves (the wire, the status mapping, the concurrency posture) is [MCP-SPEC](MCP-SPEC.md) §7; the *candidate assessment* that picked this host, and the requirements a host must meet, are [MCP-GAPS](MCP-GAPS.md) "Hosting". This file is only the procedure.

It is not a one-time procedure. A free account may be deleted at the vendor's discretion (H11), so "rebuild it from nothing" is a scenario, not a hypothetical.

## The shape

Three parties, and nothing in the middle holds state:

```
your Mac                GitHub Actions                   alwaysdata
  ssh-keygen  ──pub──────────────────────────────────▶  authorized key
              ──priv──▶  secret ALWAYSDATA_SSH_KEY
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
| private `~/.ssh/alwaysdata_ci` | GitHub secret `ALWAYSDATA_SSH_KEY` | the runner is the **client** |
| public `~/.ssh/alwaysdata_ci.pub` | alwaysdata admin ▸ Remote access ▸ SSH keys | the host is the **server** |

```bash
ssh-keygen -t ed25519 -f ~/.ssh/alwaysdata_ci -C "gh-actions deploy-mcp-endpoint" -N ""
pbcopy < ~/.ssh/alwaysdata_ci.pub     # paste into the alwaysdata admin
```

No passphrase: an unattended `scp` cannot answer a prompt. A **dedicated** key rather than your own is what bounds the blast radius, since it is revocable on its own without locking you out. Keep the local private half afterwards; it is what lets you `ssh -i ~/.ssh/alwaysdata_ci …` by hand when the pipeline fails, which is precisely when you want it.

Then set the repository's configuration. Run these in a terminal, not through an agent, so no value lands in a transcript:

```bash
R=mbackschat/a12-dmkits
gh secret   set ALWAYSDATA_SSH_KEY  --repo $R < ~/.ssh/alwaysdata_ci
gh secret   set ALWAYSDATA_API_KEY  --repo $R      # prompts; token from the account's Profile section
gh variable set ALWAYSDATA_SSH_HOST --repo $R      # ssh-<account>.alwaysdata.net
gh variable set ALWAYSDATA_SSH_USER --repo $R
gh variable set ALWAYSDATA_BIN_PATH --repo $R      # /home/<account>/bin/dmtool
gh variable set ALWAYSDATA_SITE_ID  --repo $R      # the numeric id from step 1
gh variable set ALWAYSDATA_ACCOUNT  --repo $R
gh variable set MCP_ENDPOINT_URL    --repo $R      # https://<site>/mcp
```

**Secrets are masked in run logs; variables are not.** The host, account and URL will appear in any log anyone reads. That is acceptable for a demonstration endpoint and worth knowing before you paste a log somewhere.

The pipeline **fails fast** on a missing one rather than uploading and then discovering it cannot restart, because a half-configured deploy leaves the old process serving while the log reads green.

## 3. Deploying

```bash
gh workflow run deploy-mcp-endpoint --repo mbackschat/a12-dmkits          # the latest release
gh workflow run deploy-mcp-endpoint --repo mbackschat/a12-dmkits -f tag=<release-tag>
gh workflow run deploy-mcp-endpoint --repo mbackschat/a12-dmkits -f smoke_only=true
```

Manual only, and one at a time (a concurrency group, because a second deploy would race the restart). It uploads to `<bin>.incoming` and *moves* it into place, so a partially transferred binary is never the one the platform restarts into.

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

## 5. What this endpoint is not

- **Not authenticated.** A specification SHOULD that this server does not implement. Nothing is stored, so there is no data to reach, but there is CPU to burn. Put it behind the platform's own auth or a private network if it is not meant to be public.
- **Not sized for document evaluation.** Authoring and inspection were measured flat at **~43 MiB**; a document evaluation reached **519 MiB** on a 714 KB / 100-row stress fixture. A 256 MB plan carries roughly 6× headroom for the first and cannot promise the second. *(Both figures were measured 2026-08-06, before the interpreter's preparation-ownership change; they are process RSS, a different quantity from the per-model resident cost in [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) IF253. Re-measure on the next native build.)* Nothing accumulates between requests, so per-model cost never adds up across calls: the server holds no state at all.
- **Not customer infrastructure.** The vendor may delete a free account at its discretion, so this is right where a redeploy is the whole cost. A customer who wants to run it themselves is [MG12](MCP-GAPS.md) (a container image); us hosting it as a product is [MG13](MCP-GAPS.md), and that one has a licensing consequence that cannot be undone.
- **Not yet proven in restricted mode.** An account with no payment instrument sits in *restricted mode*, whose "stricter filter rules" the vendor does not specify. An endpoint receiving automated agent traffic is plausibly what such rules exist to catch. Only a real deployment settles it.
