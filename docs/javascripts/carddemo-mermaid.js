/* ******************************************************************
 * Program     : carddemo-mermaid.js
 * Application : CardDemo
 * Type        : Documentation-site diagram renderer guard
 * Function    : Pins the Mermaid renderer to an exact version with a
 *               subresource-integrity hash, isolates a renderer
 *               failure so it cannot tear down Material's content
 *               subscription (which takes site search and
 *               table-of-contents tracking down with it), and gives
 *               every rendered diagram an accessible name and an
 *               intrinsic width so its labels stay legible at a
 *               narrow viewport.
 * Source      : diagrams/** - the legacy architecture illustrations
 *               whose Mermaid successors the published documents
 *               carry, rendered through the theme bundle rather than
 *               through the mermaid2 plugin @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 ******************************************************************
 *
 * WHY THIS FILE EXISTS, AND WHY IT MUST LOAD BEFORE THE THEME BUNDLE
 * =================================================================
 * The mermaid2 plugin logs a library version but injects nothing: the
 * built pages carry no Mermaid loader of their own. Rendering is done
 * by Material's own bundle, whose loader reads, in effect:
 *
 *   function as(){ return typeof mermaid == "undefined" ||
 *       mermaid instanceof Element
 *     ? loadScript("https://unpkg.com/mermaid@11/dist/mermaid.min.js")
 *     : of(undefined) }
 *
 *   function mount(el){ ...
 *     stream = as().pipe(tap(() => mermaid.initialize({ ... })), ...)
 *     stream.subscribe(async () => {
 *       let { svg, fn } = await mermaid.render(id, source)
 *       shadow.innerHTML = svg; fn?.(shadow) }) }
 *
 * and the mount for diagrams is one arm of an RxJS `merge` that also
 * carries code-block annotation, anchor handling, table wrapping,
 * details elements and content tabs. An unhandled error in ANY arm
 * completes the merged subscription, which is why a single renderer
 * failure was observed to leave site search stuck initialising and
 * table-of-contents tracking dead. One unreachable third-party host
 * took out two entirely unrelated local features.
 *
 * Three defects follow from that loader, and all three are fixed here:
 *
 *   1. `mermaid@11` is a FLOATING major tag. Measured, it 302-redirects
 *      to 11.16.1 today and to whatever ships next tomorrow, so the
 *      published site's rendering is not reproducible.
 *   2. It is fetched with no `integrity` attribute, so nothing verifies
 *      what arrived.
 *   3. A failure is not isolated, per the merge above.
 *
 * THE LEVER. `as()` short-circuits to `of(undefined)` when `mermaid` is
 * already defined and is not an Element. Defining `window.mermaid`
 * BEFORE the bundle executes therefore means the floating tag is never
 * contacted at all - both (1) and (2) cease to exist rather than being
 * mitigated - and the object the theme then calls is one this file
 * controls, which is what makes (3) fixable.
 *
 * `extra_javascript` cannot be used for this: MkDocs emits it AFTER the
 * theme bundle, and the mount runs synchronously on subscribe, so it
 * would be too late. The script tag is injected immediately before the
 * bundle by mkdocs_hooks.py, which is also what supplies the ordered
 * diagram names this file reads.
 *
 * WHY A PINNED URL RATHER THAN A VENDORED COPY. `dist/mermaid.min.js`
 * is 3,566,058 bytes of third-party minified JavaScript. Committing it
 * would put a 3.5 MB blob under MIT terms into a repository whose
 * source convention is a per-file Apache header, and would weigh on
 * every clone, to remove a host that mkdocs.yml already discloses and
 * accepts. Pinning the exact version, verifying it with an integrity
 * hash and making its absence graceful closes the reported defects -
 * unpinned, unverified, and fatal when unreachable - without that
 * cost. The residual difference is that a reader with no network sees
 * the fallback below rather than a rendered diagram, which is the same
 * outcome mkdocs.yml already documents, and now a deliberate one.
 *
 * WHAT THIS FILE DOES **NOT** DO. It does not call `initialize` itself
 * and does not render anything on its own. The theme remains the only
 * caller; this file is a guard in front of it, so that removing the
 * guard restores the previous behaviour exactly rather than leaving a
 * half-configured renderer.
 ****************************************************************** */

(function () {
  "use strict";

  /* The exact version measured behind the floating `mermaid@11` tag, with
     the hash of the bytes that URL actually served. Changing one without
     re-computing the other will make every diagram fail closed - which is
     the correct direction for an integrity failure, but is worth stating
     so the pairing is not broken by accident.

       curl -sSL https://unpkg.com/mermaid@11.16.1/dist/mermaid.min.js \
         | openssl dgst -sha384 -binary | openssl base64 -A          */
  var RENDERER_URL =
    "https://unpkg.com/mermaid@11.16.1/dist/mermaid.min.js";
  var RENDERER_INTEGRITY =
    "sha384-aBQXj4hK6Jm05i7aQAsUV3bLdSUrHX1BGYfMB0166TtWt/RRaw+h0Eelme9OCOvy";

  /* Configuration merged over whatever the theme passes to `initialize`.

     `useMaxWidth: false` is the load-bearing entry and the reason this
     file participates in the legibility fix at all. With the theme's
     default, Mermaid emits the SVG as `width="100%"` with `max-width` set
     to the viewBox width, so inside a 343 px host a 3,441-unit viewBox
     scales the whole coordinate system - glyphs included - by 0.0997, and
     node labels were measured at 1.59 px to 4.77 px against 16 px body
     text on the same page. Nothing clamps a minimum. Disabling it makes
     the renderer emit an intrinsic pixel width, which is what finally
     gives `div.mermaid`'s `overflow-x: auto` something to scroll; see
     rule 5 in docs/stylesheets/carddemo.css, which is inert without this.

     Every grammar in use on this site needs its own entry, because the
     flag is per-diagram-type rather than global. Measured in the built
     site: 8 flowchart, 3 pie, 1 sequence, 1 entity-relationship. */
  var LEGIBILITY_CONFIG = {
    flowchart: { useMaxWidth: false },
    sequence: { useMaxWidth: false },
    er: { useMaxWidth: false },
    pie: { useMaxWidth: false },
    gantt: { useMaxWidth: false },
    journey: { useMaxWidth: false },
    class: { useMaxWidth: false },
    state: { useMaxWidth: false },
    gitGraph: { useMaxWidth: false }
  };

  var realRenderer = null;   // the pinned module, once it has loaded
  var loadFailed = false;    // set when the pinned load cannot complete
  var loadStarted = false;   // the fetch is armed on first use, not parse
  var readyWaiters = [];     // resolvers parked while the load is in flight
  var themeConfig = null;    // whatever the theme passed to initialize()
  var configApplied = false; // initialize() is idempotent by contract here
  var renderOrdinal = 0;     // nth render() call on this page, for naming

  /* Names for the diagrams on this page, in document order, emitted by
     mkdocs_hooks.py from the nearest preceding heading. Absent or short
     is not an error: naming falls back to the grammar below. */
  function diagramNames() {
    var names = window.__carddemoDiagramNames;
    return Object.prototype.toString.call(names) === "[object Array]"
      ? names
      : [];
  }

  function settle() {
    var waiters = readyWaiters;
    readyWaiters = [];
    for (var i = 0; i < waiters.length; i++) {
      try {
        waiters[i]();
      } catch (ignored) {
        /* A waiter that throws must not stop the others from running. */
      }
    }
  }

  /* The 3.5 MB renderer is fetched on first use, not on script parse.

     Five of this site's eight pages contain no diagram at all, and an
     earlier revision fetched the bundle on every one of them - measured, on
     all five. Nothing failed, but a page with nothing to render should not
     be reaching a third-party host: it is bandwidth for no benefit and, more
     to the point, it widens the exposure to that host across pages that have
     no reason to depend on it.

     Deferring costs no latency against the theme's own sequencing. The
     theme's mermaid arm calls `initialize` synchronously and then awaits
     `render`, so the fetch still starts at the first moment the theme
     signals that a diagram exists - which is the earliest point at which the
     bundle is known to be needed. Both entry points arm it, because
     `initialize` is the earlier signal but `render` must not depend on
     having seen one. */
  function requestRenderer() {
    if (loadStarted) {
      return;
    }
    loadStarted = true;
    loadPinnedRenderer();
  }

  function whenReady() {
    requestRenderer();
    return new Promise(function (resolve) {
      if (realRenderer || loadFailed) {
        resolve();
      } else {
        readyWaiters.push(resolve);
      }
    });
  }

  /* Intercept the assignment the pinned bundle makes on its last line -
     `globalThis["mermaid"] = globalThis.__esbuild_esm_mermaid_nm["mermaid"]
     .default;` - so the real module is captured while `window.mermaid`
     continues to read as the guard the theme was compiled against. */
  function installGuard(guard) {
    try {
      Object.defineProperty(window, "mermaid", {
        configurable: true,
        get: function () {
          return guard;
        },
        set: function (assigned) {
          if (assigned && assigned !== guard) {
            realRenderer = assigned;
            settle();
          }
        }
      });
      return true;
    } catch (error) {
      /* If the property cannot be defined, do nothing at all: the theme
         then behaves exactly as it did before this file existed, which is
         a working site with the three defects above, rather than a broken
         one. Failing open is correct here precisely because the feature
         being guarded is cosmetic. */
      return false;
    }
  }

  function loadPinnedRenderer() {
    var script = document.createElement("script");
    script.src = RENDERER_URL;
    script.integrity = RENDERER_INTEGRITY;
    script.crossOrigin = "anonymous";
    script.referrerPolicy = "no-referrer";
    script.async = true;
    script.addEventListener("error", function () {
      /* Unreachable host, blocked request, or an integrity mismatch. All
         three land here, and all three must be survivable rather than
         fatal - that is defect (3). */
      loadFailed = true;
      settle();
    });
    script.addEventListener("load", function () {
      /* The bundle assigns the global on its last line, which the setter
         above captures. If it somehow did not, treat it as a failure so a
         waiter is never parked forever. */
      if (!realRenderer) {
        loadFailed = true;
      }
      settle();
    });
    (document.head || document.documentElement).appendChild(script);
  }

  function mergedConfig() {
    var merged = {};
    var key;
    if (themeConfig) {
      for (key in themeConfig) {
        if (Object.prototype.hasOwnProperty.call(themeConfig, key)) {
          merged[key] = themeConfig[key];
        }
      }
    }
    for (key in LEGIBILITY_CONFIG) {
      if (Object.prototype.hasOwnProperty.call(LEGIBILITY_CONFIG, key)) {
        var themeSection = merged[key];
        var section = {};
        var inner;
        if (themeSection && typeof themeSection === "object") {
          for (inner in themeSection) {
            if (Object.prototype.hasOwnProperty.call(themeSection, inner)) {
              section[inner] = themeSection[inner];
            }
          }
        }
        section.useMaxWidth = LEGIBILITY_CONFIG[key].useMaxWidth;
        merged[key] = section;
      }
    }
    return merged;
  }

  function escapeMarkup(text) {
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  /* Give the produced SVG an accessible name. The theme writes the markup
     into a `mode:"closed"` shadow root, so no stylesheet and no light-DOM
     selector can reach it afterwards - patching the string on the way past
     is the only point at which this is possible. Measured before this
     existed: 13 of 13 rendered diagrams were unnamed graphics carrying
     only `aria-roledescription`, and 8 of the 13 shared the identical
     value `flowchart-v2`, so the sole thing an assistive technology could
     say about them did not distinguish them from each other. */
  function nameSvg(svg, accessibleName) {
    if (typeof svg !== "string" || !accessibleName) {
      return svg;
    }
    var openTag = svg.match(/<svg\b[^>]*>/);
    if (!openTag) {
      return svg;
    }
    var tag = openTag[0];
    if (/\baria-label\s*=/.test(tag) || /\baria-labelledby\s*=/.test(tag)) {
      return svg;
    }
    var named =
      tag.replace(/>$/, ' aria-label="' + escapeMarkup(accessibleName) + '">') +
      "<title>" +
      escapeMarkup(accessibleName) +
      "</title>";
    return svg.replace(tag, named);
  }

  /* A diagram that cannot be rendered must still return well-formed markup
     and must still RESOLVE. Rejecting here is what completed the merged
     subscription and killed search and the table of contents. */
  function fallbackSvg(accessibleName, source) {
    var label = accessibleName || "Diagram";
    return (
      '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="1" ' +
      'role="img" aria-label="' +
      escapeMarkup(label + " (diagram renderer unavailable)") +
      '"><title>' +
      escapeMarkup(label + " (diagram renderer unavailable)") +
      "</title></svg>" +
      '<pre class="carddemo-diagram-fallback"><code>' +
      escapeMarkup(source == null ? "" : source) +
      "</code></pre>"
    );
  }

  var guardObject = {
    /* Recorded rather than forwarded. The theme calls this synchronously
       while the pinned bundle may still be in flight, and Mermaid's own
       initialize is not safe to call before the module exists. */
    initialize: function (config) {
      themeConfig = config || {};
      configApplied = false;
      /* The theme's earliest signal that this page has a diagram. */
      requestRenderer();
      return undefined;
    },

    /* Mermaid 11 resolves `{ svg, bindFunctions }`; the theme destructures
       `{ svg, fn }` and calls `fn?.(shadowRoot)`, so `fn` is aliased here.
       That mismatch predates this file and is harmless either way, but
       aliasing it means the theme's bind step starts working rather than
       being silently skipped. */
    render: function (id, source, container) {
      var ordinal = renderOrdinal++;
      var names = diagramNames();
      var accessibleName = names[ordinal];

      return whenReady().then(function () {
        if (!realRenderer || typeof realRenderer.render !== "function") {
          return { svg: fallbackSvg(accessibleName, source), fn: undefined };
        }
        if (!configApplied) {
          configApplied = true;
          try {
            if (typeof realRenderer.initialize === "function") {
              realRenderer.initialize(mergedConfig());
            }
          } catch (error) {
            /* A rejected configuration must not stop rendering; the
               diagram is still better than nothing at the default. */
            configApplied = true;
          }
        }
        return Promise.resolve(
          realRenderer.render(id, source, container)
        ).then(
          function (result) {
            var svg = result && result.svg;
            var bind = result && (result.bindFunctions || result.fn);
            return {
              svg: nameSvg(svg, accessibleName),
              bindFunctions: bind,
              fn: bind
            };
          },
          function () {
            /* A malformed diagram fails alone. Before this, one bad
               stadium label was enough to complete the merged
               subscription for the whole page. */
            return {
              svg: fallbackSvg(accessibleName, source),
              fn: undefined
            };
          }
        );
      });
    },

    /* Present so that any other theme call path finds a function rather
       than undefined and throws no ReferenceError-shaped error. */
    run: function () {
      return Promise.resolve();
    },

    contentLoaded: function () {
      return undefined;
    },

    /* Lets a reader, and the runtime check in the onboarding guide,
       establish which renderer is actually in use. */
    carddemoGuard: {
      pinnedUrl: RENDERER_URL,
      integrity: RENDERER_INTEGRITY,
      isLoaded: function () {
        return Boolean(realRenderer);
      },
      hasFailed: function () {
        return loadFailed;
      }
    }
  };

  /* Installing the guard is unconditional and cheap; it is what makes the
     theme's loader short-circuit so the floating `mermaid@11` tag is never
     requested. The pinned fetch is armed separately, on first use. */
  installGuard(guardObject);
})();
