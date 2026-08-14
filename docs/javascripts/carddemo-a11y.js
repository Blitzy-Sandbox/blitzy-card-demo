/* ******************************************************************
 * Program     : carddemo-a11y.js
 * Application : CardDemo
 * Type        : Documentation-site keyboard accessibility correction
 * Function    : Supplies the keyboard behaviour the theme's CSS-only
 *               toggles do not have - Space activation, Escape to
 *               close, and focus moved into and returned from the
 *               mobile navigation drawer and the search panel - and
 *               gives each rendered diagram container an accessible
 *               name, role and focus stop so its new scroll affordance
 *               is reachable.
 * Source      : app/csd/CARDDEMO.CSD by way of the documents that
 *               describe it - this script governs the published
 *               rendering of the migration's evidence documents, and
 *               is registered through extra_javascript in mkdocs.yml
 *               @ 7756d89
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
 * WHAT WAS MEASURED, AND WHY MARKUP ALONE COULD NOT FIX IT
 * ========================================================
 * Material implements its mobile navigation drawer and its search panel
 * as CSS-only checkbox toggles: a `display:none` `input.md-toggle` and a
 * `<label for>` styled as a button. Measured at 375 px on the built site,
 * that construction produced these defects:
 *
 *   1. NO KEYBOARD PATH TO THE DRAWER AT ALL. The label carried no
 *      `tabindex`, so it could not be focused, and the checkbox is
 *      `display:none`, so it could not be focused either. `element.focus()`
 *      on both left `document.activeElement` as `<body>`. On a narrow
 *      viewport the entire site navigation was keyboard-unreachable.
 *   2. SPACE DOES NOT ACTIVATE A LABEL. Chrome synthesises a click from
 *      Enter on a focused `<label>` but not from Space - Space is the
 *      *checkbox's* key, and the checkbox cannot be focused. So merely
 *      adding `tabindex="0"` (which mkdocs_hooks.py now does) yields a
 *      control that works with Enter and silently does nothing with
 *      Space, which is the opposite of what a control announced as a
 *      button leads a reader to expect. Worse, on a page longer than the
 *      viewport, Space would scroll the page instead - a wrong action
 *      rather than no action.
 *   3. ESCAPE DID NOT CLOSE THE DRAWER, and opening it moved focus
 *      nowhere, so with the drawer open and the page dimmed behind it the
 *      next Tab went to the search field *behind* the scrim.
 *   4. ESCAPE FROM SEARCH DUMPED FOCUS TO `<body>` rather than returning
 *      it to the control that opened the panel, so the next Tab restarted
 *      from the top of the page.
 *
 * Items 1 and the naming half are markup, and are done at build time in
 * mkdocs_hooks.py where they cost nothing at runtime. Items 2, 3 and 4
 * are behaviour: no attribute expresses "synthesise a click from Space"
 * or "return focus to the opener", so they need script. This file is
 * that, and nothing more - it adds no styling and no markup of its own
 * beyond attributes on elements the theme has already produced.
 *
 * WHY IT IS SAFE TO ADD BEHAVIOUR TO A THEME WE DO NOT CONTROL. Every
 * handler here is additive and idempotent: it drives the theme's own
 * checkbox through the same `click()` the mouse path uses, rather than
 * reimplementing the open/close animation or touching the theme's
 * classes. If Material later ships these behaviours itself, this file
 * becomes redundant rather than conflicting, because a second click on
 * an already-correct toggle is what it avoids by reading `checked`
 * first.
 ****************************************************************** */

(function () {
  "use strict";

  /* The two CSS-only toggles, and the element each one should hand focus
     to when it opens. Keyed by the checkbox id the theme emits. */
  var TOGGLES = {
    __drawer: {
      panelSelector: ".md-sidebar--primary .md-nav--primary",
      focusOnOpenSelector: ".md-sidebar--primary .md-nav--primary a.md-nav__link"
    },
    __search: {
      panelSelector: ".md-search__inner",
      focusOnOpenSelector: ".md-search__input"
    }
  };

  function toggleCheckbox(id) {
    var element = document.getElementById(id);
    return element && element.type === "checkbox" ? element : null;
  }

  function labelsFor(id) {
    return Array.prototype.slice.call(
      document.querySelectorAll('label[for="' + id + '"]')
    );
  }

  /* Only the labels the build step made into reachable controls are driven
     from the keyboard. The overlay/scrim labels are pointer affordances and
     deliberately stay out of the tab order - naming and focusing a scrim
     would add a stop that does nothing a reader can perceive. */
  function isKeyboardControl(label) {
    return label.getAttribute("tabindex") === "0";
  }

  function setChecked(id, wanted, moveFocus) {
    var checkbox = toggleCheckbox(id);
    if (!checkbox || checkbox.checked === wanted) {
      return false;
    }
    /* Click the checkbox rather than assigning `.checked`: the theme
       listens for the change the click produces, and assigning the
       property fires no event, which would open the panel visually while
       leaving the theme's own state machine behind. */
    checkbox.click();

    var config = TOGGLES[id];
    if (wanted && moveFocus && config) {
      var target = document.querySelector(config.focusOnOpenSelector);
      if (target) {
        /* One frame, so the theme's transition has begun and the target is
           no longer `visibility: hidden` from stylesheet rule 4 - a hidden
           element cannot take focus, and this is the ordering that made an
           earlier attempt appear to do nothing. */
        window.requestAnimationFrame(function () {
          try {
            target.focus();
          } catch (ignored) {
            /* Non-focusable target: leave focus where it is rather than
               throwing inside an event handler. */
          }
        });
      }
    }
    return true;
  }

  function returnFocusTo(id) {
    var candidates = labelsFor(id).filter(isKeyboardControl);
    for (var i = 0; i < candidates.length; i++) {
      var rect = candidates[i].getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        try {
          candidates[i].focus();
          return true;
        } catch (ignored) {
          /* Try the next candidate. */
        }
      }
    }
    return false;
  }

  /* Defect 2. Space on a focused toggle label must do what Enter already
     does. `preventDefault` is what stops the page scrolling instead. */
  function installSpaceActivation() {
    document.addEventListener(
      "keydown",
      function (event) {
        if (event.key !== " " && event.key !== "Spacebar") {
          return;
        }
        if (event.altKey || event.ctrlKey || event.metaKey) {
          return;
        }
        var label = event.target;
        if (
          !label ||
          label.tagName !== "LABEL" ||
          !isKeyboardControl(label)
        ) {
          return;
        }
        var id = label.getAttribute("for");
        if (!id || !Object.prototype.hasOwnProperty.call(TOGGLES, id)) {
          return;
        }
        event.preventDefault();
        var checkbox = toggleCheckbox(id);
        if (checkbox) {
          setChecked(id, !checkbox.checked, true);
        }
      },
      true
    );
  }

  /* Defects 3 and 4. Escape closes whichever panel is open and returns
     focus to the control that opens it. Search is checked first because
     the theme allows it to be opened on top of an open drawer, so the
     innermost surface must be the one Escape dismisses. */
  function installEscapeToClose() {
    document.addEventListener(
      "keydown",
      function (event) {
        if (event.key !== "Escape" && event.key !== "Esc") {
          return;
        }
        var search = toggleCheckbox("__search");
        if (search && search.checked) {
          if (setChecked("__search", false, false)) {
            returnFocusTo("__search");
            event.preventDefault();
          }
          return;
        }
        var drawer = toggleCheckbox("__drawer");
        if (drawer && drawer.checked) {
          if (setChecked("__drawer", false, false)) {
            returnFocusTo("__drawer");
            event.preventDefault();
          }
        }
      },
      true
    );
  }

  /* Opening the drawer with the mouse leaves focus on `<body>`, so a
     reader who taps the hamburger and then switches to the keyboard gets
     the same nowhere-to-start problem. Moving focus on the theme's own
     change event covers the pointer path without duplicating the
     keyboard one. */
  /* Focus enters the drawer whenever the drawer opens, by whatever route.
     There are three, and an earlier revision of this function covered only
     one of them.

     It tested `document.activeElement === document.body`, which is true for
     a pointer open and false for a keyboard one: Chrome synthesises the
     click for ENTER on a `<label>` natively, so Enter never reaches the
     handler in installSpaceActivation and leaves focus sitting on the
     hamburger. Measured, Space opened the drawer and moved focus to "Home"
     while Enter opened it and left focus behind - polled to 2.4s, so a
     settled difference rather than a race. Two keys that activate the same
     control should not land the user in two different places.

     The condition is now the one that actually describes the intent: the
     drawer just became checked and focus is not already inside it. That is
     true for pointer, Enter and Space alike, and it is idempotent with the
     move setChecked already performs for Space, because both target the
     same element. */
  function installFocusOnOpen() {
    var drawer = toggleCheckbox("__drawer");
    if (!drawer) {
      return;
    }
    drawer.addEventListener("change", function () {
      if (!drawer.checked) {
        return;
      }
      var sidebar = document.querySelector(".md-sidebar--primary");
      if (sidebar && sidebar.contains(document.activeElement)) {
        return;
      }
      var target = document.querySelector(
        TOGGLES.__drawer.focusOnOpenSelector
      );
      if (target) {
        /* One frame, for the same reason setChecked waits one: stylesheet
           rule 4 keeps the closed drawer `visibility: hidden`, and a hidden
           element cannot take focus. */
        window.requestAnimationFrame(function () {
          try {
            target.focus();
          } catch (ignored) {
            /* Leave focus alone rather than throwing. */
          }
        });
      }
    });
  }

  /* Rendered diagrams. The SVG itself is named by carddemo-mermaid.js on
     its way into a closed shadow root; what remains is the host element,
     which is what an assistive technology actually reaches and which
     stylesheet rule 5 has just made a scroll container. A scroll container
     that cannot be focused cannot be scrolled by keyboard, so it is given
     a tab stop - named, so it is not one more anonymous stop of the kind
     stylesheet rule 4 removes.

     Names come from the same ordered, build-time list the renderer guard
     reads, assigned in document order. Document order is reliable here
     because Material replaces each `pre.mermaid` in place, so the nth
     `div.mermaid` to appear is the nth diagram on the page. */
  function nameRenderedDiagrams() {
    var names = window.__carddemoDiagramNames;
    if (Object.prototype.toString.call(names) !== "[object Array]") {
      names = [];
    }

    function apply() {
      var hosts = document.querySelectorAll("div.mermaid");
      for (var i = 0; i < hosts.length; i++) {
        var host = hosts[i];
        if (host.getAttribute("data-carddemo-named") === "true") {
          continue;
        }
        var name = names[i];
        if (!name) {
          continue;
        }
        host.setAttribute("role", "img");
        host.setAttribute("aria-label", name);
        /* Only a container that can actually scroll earns a tab stop. */
        if (host.scrollWidth > host.clientWidth) {
          host.setAttribute("tabindex", "0");
          host.setAttribute(
            "aria-description",
            "Scrollable diagram. Use the arrow keys to pan."
          );
        }
        host.setAttribute("data-carddemo-named", "true");
      }
    }

    apply();
    if (typeof window.MutationObserver === "function") {
      var observer = new window.MutationObserver(function () {
        apply();
      });
      observer.observe(document.body, { childList: true, subtree: true });
    }
  }

  function start() {
    try {
      installSpaceActivation();
      installEscapeToClose();
      installFocusOnOpen();
      nameRenderedDiagrams();
    } catch (error) {
      /* A documentation site must render even if this correction cannot
         install. Failing quietly here is the same reasoning the renderer
         guard uses: the feature is an improvement on the theme, not a
         prerequisite for reading the page. */
      if (window.console && typeof window.console.warn === "function") {
        window.console.warn(
          "[carddemo] accessibility corrections did not install:",
          error
        );
      }
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
