# The oss-cli website

One page, one file. `index.html` carries its own CSS and JavaScript and loads
nothing from any other host, so it needs no build step and no dependency
updates — deploying is copying a directory.

```
site/
  index.html   the page
  _headers     response headers Cloudflare Pages applies
  README.md    this file
```

## Deploying to Cloudflare Pages

### From the GitHub repository (recommended)

Pages redeploys on every push to `main`, so the site follows the code.

1. Cloudflare dashboard → **Workers & Pages** → **Create** → **Pages** →
   **Connect to Git**, and pick `ramanathan1504/oss-cli`.
2. Set the build configuration:

   | Field | Value |
   |---|---|
   | Framework preset | None |
   | Build command | *(leave empty)* |
   | Build output directory | `site` |
   | Production branch | `main` |

3. **Save and Deploy**. The first build publishes to
   `<project>.pages.dev`.

### Or from your machine

```sh
npx wrangler pages deploy site --project-name=oss-cli
```

## Pointing ubuos.com at it

In the Pages project → **Custom domains** → **Set up a custom domain** → enter
`ubuos.com`. Cloudflare adds the DNS records itself when the domain is already
on your account, and issues the certificate within a few minutes.

Add `www.ubuos.com` as a second custom domain if you want it to resolve too;
Cloudflare redirects one to the other.

## Changing the page

Edit `index.html` and open it in a browser — there is nothing to compile or
serve. A few things worth keeping intact:

- **Both themes.** Colours are CSS custom properties defined once on `:root`,
  overridden under `prefers-color-scheme: dark`, then again under
  `[data-theme="dark"]` and `[data-theme="light"]` so the on-page toggle wins in
  both directions. Style through the tokens; do not hard-code a colour inside a
  media query.
- **No external requests.** `_headers` sets a Content-Security-Policy that
  forbids them. Adding a font or script from a CDN will be blocked in
  production while still working locally, which is the failure that is easy to
  miss.
- **Real output.** The terminal panel is genuine `oss-cli review` output, not a
  mock-up. If the command's output changes, update the panel to match rather
  than letting the page advertise something the tool no longer prints.
- **No version numbers in download steps.** The Linux and Windows instructions
  ask the GitHub API for the newest release and read the asset URL out of the
  response, so they keep working after every release with no edit here. The
  obvious shortcut — `releases/latest/download/oss-cli-1.3.1.jar` — looks
  self-updating and is not: the asset filename carries the version, so that URL
  starts returning 404 the day the next release ships. Do not reintroduce it.
