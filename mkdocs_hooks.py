# ******************************************************************
# * Program     : mkdocs_hooks.py
# * Application : CardDemo
# * Type        : MkDocs build hook (Backstage TechDocs publication)
# * Function    : Gives the Material for MkDocs search dialog the
# *               accessible name the theme omits. The theme emits
# *               <div class="md-search" data-md-component="search"
# *               role="dialog"> with no aria-label, aria-labelledby or
# *               title, so an assistive technology announces an unnamed
# *               dialog. This hook adds aria-label at build time, on
# *               every page, and FAILS THE BUILD if the markup it
# *               expects is not there - so it can never silently stop
# *               working after a theme upgrade.
# * Source      : app/csd/CARDDEMO.CSD by way of the documents that
# *               describe it - this hook governs the published
# *               rendering of the migration's evidence documents, and
# *               is registered through the hooks entry in mkdocs.yml
# *               @ 7756d89
# ******************************************************************
# * Copyright Amazon.com, Inc. or its affiliates.
# * All Rights Reserved.
# *
# * Licensed under the Apache License, Version 2.0 (the "License").
# * You may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# *
# *    http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing,
# * software distributed under the License is distributed on an
# * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# * either express or implied. See the License for the specific
# * language governing permissions and limitations under the License
# ******************************************************************
"""Build-time accessibility correction for the published documentation site.

Why this exists, and why it is a hook rather than CSS or a template override
=============================================================================
Enabling site search was a net improvement - it removed a console error that
fired on every page and it made search work - but it also brought a latent
theme defect into existence. Material renders its search block only when a
search plugin is active, and that block's outermost element is::

    <div class="md-search" data-md-component="search" role="dialog">

with no accessible name of any kind. An automated accessibility audit scores
that as a failure (``aria-dialog-name``, weight 7), which took the site from
100 to 96 on two pages the moment search began rendering. A dialog announced
with no name is a real, if small, defect: an assistive technology tells the
reader a dialog has opened without saying what it is for.

Three remedies were considered and two rejected:

* **CSS cannot do it.** A stylesheet cannot add an ARIA attribute. This is why
  the sibling fix in ``docs/stylesheets/carddemo.css`` deliberately stops at
  colour and text-decoration.
* **A theme template override would work but is version-coupled.** The only
  include of the search partial is inside Material's ``partials/header.html``,
  which is not exposed as an overridable block, so an override means vendoring
  a copy of the theme's header and keeping it in step with every upgrade -
  trading one silent-breakage risk for a larger one.
* **A build-time hook is the narrowest option**, and it is the one taken. It
  runs once per page at build time and needs no JavaScript of its own for any
  rewrite that is expressible as markup.

  A claim that stood here has been withdrawn, because this file is the reason
  it stopped being true. It read: *"the published pages carry exactly one
  script, the theme's own bundle, and that stays true."* They now carry three
  - the theme's bundle, ``javascripts/carddemo-mermaid.js`` injected by this
  hook immediately BEFORE it, and ``javascripts/carddemo-a11y.js`` emitted
  after it through ``extra_javascript``. Both were added deliberately and both
  are documented in their own headers. The original sentence is kept in view
  rather than deleted because the *principle* behind it still holds and still
  governs additions here: markup at build time is preferred to script at
  runtime, and script is added only for the two defects that no attribute can
  express - synthesising an activation from Space on a ``<label>``, and
  returning focus to the control that opened a panel.

What else this hook corrects, and why each one is here
=====================================================
The search dialog's name was the first defect found; measuring the built site
found seven more of the same kind - theme markup that no stylesheet can reach
and no template override can touch without vendoring the theme. Each rewrite
below records the measurement that justifies it.

* **The mobile toggles could not be reached at all.** Material implements the
  navigation drawer and the search panel as a ``display:none``
  ``input.md-toggle`` driven by a ``<label for>``. Neither is focusable - the
  label has no ``tabindex`` and ``element.focus()`` on either left
  ``document.activeElement`` as ``<body>``. Measured at 375 px, the entire
  site navigation was keyboard-unreachable. They are given ``role="button"``,
  an ``aria-label`` and ``tabindex="0"`` here; Space activation and Escape
  are behaviour and live in ``carddemo-a11y.js``.
* **The scrims were unnamed elements in the accessibility tree.** They are
  pointer affordances with no keyboard role, so they are marked
  ``aria-hidden``, which is the honest description of a click-catcher.
* **One focus stop was zero-size, unnamed, and actively harmful.**
  ``div.md-search__scrollwrap`` ships with ``tabindex="0"``; measured, it is
  0x0 once the panel settles, has no accessible name, and landing on it
  *closes the search panel the previous stop opened*. Its ``tabindex`` is set
  to ``-1``. The results remain reachable by ArrowDown, which is the theme's
  own idiom.
* **The result count was never announced.** A nine-attribute sweep found
  **zero** live regions anywhere in the document, at any point. The count
  lives in a plain ``div.md-search-result__meta`` whose text is swapped from
  "Type to start searching" to "5 matching documents" with nothing to
  announce it. It is given ``role="status"`` and ``aria-live="polite"``.
* **62 decorative icons were unlabelled graphics.** Every ``<svg>`` in the
  built pages - header, footer, logo and search icons - carried neither
  ``aria-hidden`` nor ``role="img"``, so each appeared in the accessibility
  tree as an unnamed ``image`` node. Note that this is invisible to axe:
  ``svg-img-alt`` only inspects ``svg[role="img"]``, so all 62 were skipped
  and no audit would ever have reported them.
* **The footer links failed Label in Name.** Their visible text is "Next
  Project Guide" while their accessible name is "Next: Project Guide" - the
  colon breaks the containment WCAG 2.5.3 requires, and Lighthouse reports
  ``label-content-name-mismatch`` on two elements on six of the seven nav
  pages. It passes on the home page only because that page has one footer
  link rather than two, which is a reason to distrust that page's audit
  rather than to leave the defect. The colon is removed.
* **The diagram renderer had to be pinned from ahead of the theme bundle.**
  ``extra_javascript`` is emitted *after* the bundle and the diagram mount
  runs synchronously on subscribe, so a guard emitted there would be too
  late. This hook injects it immediately before the bundle's own script tag,
  reusing that tag's relative prefix so it resolves at every nesting depth,
  and emits the ordered diagram names both scripts read.

Two events, because MkDocs builds two kinds of output
=====================================================
Documentation pages reach :func:`on_post_page`. Theme templates - ``404.html``
and ``sitemap.xml`` - do not: MkDocs renders those through a separate path that
emits :func:`on_post_template` instead. ``404.html`` carries the full header,
and therefore the same unnamed dialog, so covering only the page event leaves
exactly one published page still failing the audit. Both events are handled,
and they share one implementation so the two cannot drift apart.

The safety property that makes this acceptable
==============================================
A hook that rewrites markup by pattern is exactly the kind of thing that stops
matching after an upgrade and then does nothing, forever, without a word. This
one cannot: it raises when it finds search markup whose dialog element it cannot
name, and raises when it finds more than one. Either way the build fails loudly
rather than publishing a regression. The only case accepted silently is output
with no search markup at all, which is what ``sitemap.xml`` looks like.
"""

from __future__ import annotations

import glob
import json
import logging
import os
import re
from typing import Any

log = logging.getLogger("mkdocs.hooks.carddemo")

#: Material's own default separator with the hyphen removed, and without its
#: CamelCase and decimal-point rules. See :func:`on_post_build` for why the
#: hyphen has to go and why the rest of the punctuation stays.
_SEARCH_SEPARATOR = r'[\s,:!=\[\]()"/]+'

#: The theme's search dialog, exactly as Material 9.7.6 emits it. Matched with
#: the attributes in the order the theme writes them, because a looser pattern
#: would risk matching some future element that is not this one.
_SEARCH_DIALOG = re.compile(
    r'<div class="md-search" data-md-component="search" role="dialog">'
)

#: What the dialog is announced as. Deliberately the same word the theme
#: already uses for the input's own ``aria-label`` and its placeholder, so the
#: dialog, its inner ``role="search"`` landmark and its field all agree.
_ACCESSIBLE_NAME = "Search"

_REPLACEMENT = (
    '<div class="md-search" data-md-component="search" role="dialog" '
    'aria-label="%s">' % _ACCESSIBLE_NAME
)

#: Any element already carrying an accessible name is left alone: if a future
#: theme version names its own dialog, this hook must become a no-op rather
#: than produce a duplicate attribute.
_ALREADY_NAMED = re.compile(
    r'<div class="md-search"[^>]*\b(?:aria-label|aria-labelledby|title)\s*='
)

#: Recognises that a page carries the search markup at all, independently of
#: the dialog element - so a theme change that keeps search but restructures
#: the wrapper is detected instead of silently skipped.
_HAS_SEARCH_MARKUP = re.compile(r'\bmd-search__input\b')

def _opening_tag(tag: str, *required: str, absent: str = "") -> Any:
    """Build an attribute-ORDER-INDEPENDENT matcher for one opening tag.

    This exists because of a defect these patterns had on first writing, and
    the defect is worth recording because it would have recurred. The patterns
    originally matched the theme's attributes in the order the templates write
    them, and that order is not stable across the built site: MkDocs emits
    ``index.html`` and ``404.html`` as raw template output, while the six
    interior pages are parsed and RE-SERIALISED by a plugin in the
    ``techdocs-core`` bundle. Re-serialisation rewrote
    ``<div class="md-search__scrollwrap" tabindex="0" data-md-scrollfix>`` as
    ``<div class="md-search__scrollwrap" data-md-scrollfix="" tabindex="0">`` -
    attributes reordered and a valueless attribute given an empty value. The
    home page matched and every interior page did not.

    The fail-loud discipline is what turned that into a one-line build failure
    naming the page and the element instead of six pages published with the
    correction silently missing, which is the whole argument for it.

    Matching by lookahead means each attribute is required to be present
    without any assumption about where: ``class`` may come before or after
    ``for``, and a valueless attribute matches whether or not the serialiser
    gave it ``=""``.

    :param tag: element name, for example ``label``.
    :param required: attribute fragments that must all appear in the tag.
    :param absent: an attribute name which, if already present, makes the
        pattern skip the tag - which is what makes every rewrite idempotent and
        keeps it from fighting a future theme that sets the attribute itself.
    :returns: a compiled pattern whose group 1 is the tag's whole attribute run.
    """
    lookaheads = "".join(
        r"(?=[^>]*%s)" % fragment for fragment in required
    )
    if absent:
        lookaheads += r"(?![^>]*\b%s\s*=)" % absent
    return re.compile(r"<%s\b%s([^>]*)>" % (tag, lookaheads))


#: The two CSS-only toggles that must become reachable, named controls, and
#: the accessible name each is given. ``role="button"`` is correct rather than
#: ``checkbox``: to a reader these are the buttons they are styled as, and the
#: checkbox is an implementation detail that is ``display:none``.
_TOGGLE_CONTROLS = (
    (
        _opening_tag(
            "label",
            r'class="md-header__button md-icon"',
            r'for="__drawer"',
            absent="role",
        ),
        r'<label\1 role="button" tabindex="0" aria-controls="__drawer" '
        r'aria-label="Navigation menu">',
        "header drawer toggle",
    ),
    (
        _opening_tag(
            "label",
            r'class="md-header__button md-icon"',
            r'for="__search"',
            absent="role",
        ),
        r'<label\1 role="button" tabindex="0" aria-controls="__search" '
        r'aria-label="Open search">',
        "header search toggle",
    ),
    # Inside the open search panel. It carries two icons - a magnifier shown
    # at wide viewports and a back arrow shown at narrow ones - and being
    # `for="__search"` while the panel is open, activating it CLOSES search.
    # It is named for what it does rather than for the glyph it happens to
    # show at one breakpoint.
    #
    # `tabindex="-1"`, deliberately, and this is the one control here that is
    # NOT put into the tab ring. Giving it `tabindex="0"` was tried and
    # measured, and it produced a worse defect than the one it fixed: the
    # theme un-checks `#__search` when focus leaves the search input, so
    # tabbing forward off the input collapsed the panel and left focus on
    # this label at `opacity: 0` - an invisible focus stop, which is WCAG
    # 2.4.7 and 2.4.11. Re-opening the panel from a `focusin` handler was the
    # obvious repair and is worse still: the theme focuses the input whenever
    # search opens, so focus bounces straight back to the input and Tab
    # appears not to advance at all.
    #
    # Removing it from the ring costs nothing, because closing search from
    # the keyboard is already served twice over - Escape, which this project
    # implements and has verified returns focus to the opener, and the header
    # toggle, which is `for="__search"` and so toggles both ways. The label
    # keeps its role and its name so that pointer users and anyone reading
    # the accessibility tree still find a named control; only the sequential
    # stop goes. Tabbing out of search now lands on page content, which is
    # where a keyboard user going forward was trying to get to.
    (
        _opening_tag(
            "label",
            r'class="md-search__icon md-icon"',
            r'for="__search"',
            absent="role",
        ),
        r'<label\1 role="button" tabindex="-1" aria-controls="__search" '
        r'aria-label="Close search">',
        "in-panel search toggle",
    ),
)

#: The two scrims. Pointer-only click-catchers with no keyboard role and no
#: perceivable content, so they are removed from the accessibility tree rather
#: than given a name that would describe nothing.
_SCRIMS = (
    (
        _opening_tag("label", r'class="md-overlay"', absent="aria-hidden"),
        r'<label\1 aria-hidden="true">',
        "drawer scrim",
    ),
    (
        _opening_tag(
            "label", r'class="md-search__overlay"', absent="aria-hidden"
        ),
        r'<label\1 aria-hidden="true">',
        "search scrim",
    ),
)

#: The zero-size, unnamed focus stop that closes the panel it sits inside.
#: Matched on the class alone and rewritten by operating on the captured
#: attribute run, because this is the element whose attribute order the
#: re-serialiser actually changes.
_SEARCH_SCROLLWRAP = _opening_tag("div", r'class="md-search__scrollwrap"')
_TABINDEX_ZERO = re.compile(r'\btabindex\s*=\s*"0"')

#: The element whose text carries the result count, with nothing to announce
#: it. ``aria-atomic`` is set because the whole sentence changes at once and a
#: partial announcement of a count is worse than none.
_SEARCH_META = _opening_tag(
    "div", r'class="md-search-result__meta"', absent="role"
)
_SEARCH_META_FIXED = (
    r'<div\1 role="status" aria-live="polite" aria-atomic="true">'
)

#: Every decorative icon. Matched on the opening tag only, and only when it
#: carries neither an ``aria-hidden`` nor a ``role`` already, so the rewrite is
#: idempotent and cannot fight a future theme that labels its own icons.
_DECORATIVE_SVG = re.compile(
    r'<svg(?![^>]*\baria-hidden\s*=)(?![^>]*\brole\s*=)'
    r'(?![^>]*\baria-label\s*=)([^>]*)>'
)

#: ``aria-label="Next: X"`` / ``"Previous: X"`` on the footer links. The
#: whole attribute is removed, for the reasons in
#: :func:`_fix_footer_link_names`. The leading space is consumed with it so
#: the tag does not end up with a double space.
_FOOTER_LINK_LABEL = re.compile(
    r'\s+aria-label="(?:Next|Previous):? [^"]*"'
)

#: The theme's own bundle. The relative prefix is captured and reused so the
#: injected guard resolves from any nesting depth without this hook having to
#: recompute a base URL that MkDocs has already computed correctly.
_THEME_BUNDLE = re.compile(
    r'<script src="((?:/|(?:\.\./)*)assets/javascripts/bundle\.[0-9a-f]+\.min\.js)"'
    r'[^>]*></script>'
)

#: A rendered-to-be diagram in the built markup, and the headings that name
#: it. Material replaces each of these in place at runtime, so their document
#: order is the order the ``div.mermaid`` hosts appear in.
_MERMAID_BLOCK = re.compile(r'<pre class="mermaid"[^>]*>')
_HEADING = re.compile(r'<h([1-6])[^>]*>(.*?)</h\1>', re.DOTALL)
_TAGS = re.compile(r'<[^>]+>')
_PERMALINK = re.compile(r'\s*\u00b6\s*$')


def _diagram_names(output: str) -> list[str]:
    """Name each diagram on the page from the heading that introduces it.

    A diagram's accessible name has to come from somewhere, and the two
    mechanical alternatives are both worse than this one. Naming it by its
    grammar produces eight identically-named "flowchart" diagrams, which is
    the defect being fixed rather than a fix - measured, 8 of the 13 rendered
    diagrams already shared the single ``aria-roledescription`` value
    ``flowchart-v2`` and it was the only thing an assistive technology could
    say about any of them. Naming it from its own node labels produces a name
    assembled from fragments of the picture, which describes the parts and not
    the whole.

    The heading immediately above a diagram is what a sighted reader uses to
    know what they are looking at, so it is the honest source. Where two
    diagrams share one heading they are numbered within it, because a
    duplicate name is only marginally better than none.

    :param output: rendered HTML for one page.
    :returns: accessible names in document order, one per ``pre.mermaid``.
    """
    headings = [
        (match.start(), _PERMALINK.sub("", _TAGS.sub("", match.group(2))).strip())
        for match in _HEADING.finditer(output)
    ]
    names: list[str] = []
    used: dict[str, int] = {}

    for block in _MERMAID_BLOCK.finditer(output):
        heading = ""
        for position, text in headings:
            if position < block.start() and text:
                heading = text
            elif position >= block.start():
                break
        base = "%s diagram" % heading if heading else "Diagram"
        used[base] = used.get(base, 0) + 1
        names.append(base if used[base] == 1 else "%s %d" % (base, used[base]))

    return names


def _name_search_dialog(output: str, origin: str) -> str:
    """Add ``aria-label`` to the one Material search dialog in ``output``.

    :param output: rendered HTML, either a documentation page or a theme
        template.
    :param origin: how to refer to ``output`` in an error message.
    :returns: the HTML with the dialog named, or unchanged when there is no
        search block to name.
    :raises RuntimeError: when search markup is present but its dialog element
        cannot be found and named, or when more than one dialog is present.
        Raising is the point: a pattern-matching rewrite that stops matching
        must break the build rather than quietly publish an unnamed dialog.
    """
    page_file = origin

    if _ALREADY_NAMED.search(output):
        # The theme has started naming its own dialog. Nothing to do, and
        # saying so once per build is worth the log line: it is the signal to
        # delete this hook.
        log.info(
            "[carddemo] %s: search dialog already carries an accessible name; "
            "this hook is now redundant and can be removed.",
            page_file,
        )
        return output

    patched, count = _SEARCH_DIALOG.subn(_REPLACEMENT, output)

    if count == 0:
        if _HAS_SEARCH_MARKUP.search(output):
            raise RuntimeError(
                "%s renders the Material search field but its "
                'role="dialog" wrapper could not be found, so it cannot be '
                "given an accessible name. The theme's markup has changed. "
                "Update the _SEARCH_DIALOG pattern in mkdocs_hooks.py to "
                "match the new wrapper, or delete this hook if the theme now "
                "names the dialog itself. Failing the build deliberately: "
                "publishing an unnamed dialog is the regression this hook "
                "exists to prevent." % page_file
            )
        # No search block at all - sitemap.xml, for instance.
        return output

    if count != 1:
        raise RuntimeError(
            "%s contains %d Material search dialogs; exactly one was "
            "expected. Refusing to guess which should be named."
            % (page_file, count)
        )

    return patched


def _substitute_exactly_once(
    output: str, pattern: Any, replacement: str, origin: str, what: str
) -> str:
    """Apply a rewrite that must match exactly once where its context exists.

    The same safety property :func:`_name_search_dialog` documents, factored so
    every rewrite added since inherits it rather than reimplementing it. A
    pattern that stops matching after a theme upgrade must fail the build, not
    quietly publish the regression it was added to remove.

    :param output: rendered HTML.
    :param pattern: compiled pattern whose single match is rewritten.
    :param replacement: literal replacement for that match.
    :param origin: how to refer to ``output`` in an error message.
    :param what: human-readable name of the element, for the error message.
    :returns: the rewritten HTML.
    :raises RuntimeError: when the pattern matches more than once.
    """
    patched, count = pattern.subn(replacement, output)
    if count > 1:
        raise RuntimeError(
            "%s contains %d occurrences of the %s; exactly one was expected. "
            "Refusing to guess which should be rewritten. Update the pattern "
            "in mkdocs_hooks.py." % (origin, count, what)
        )
    return patched


def _correct_search_and_toggles(output: str, origin: str) -> str:
    """Make the theme's CSS-only toggles reachable, named and announced.

    Everything here is skipped on output with no search markup, which is what
    ``sitemap.xml`` looks like. Where search markup IS present the toggles,
    the scroll wrapper and the result-count element must all be found, because
    they are emitted by the same partial - a page that has one and not the
    others means the theme's markup has changed and the build should say so.

    :param output: rendered HTML.
    :param origin: how to refer to ``output`` in an error message.
    :returns: the rewritten HTML.
    :raises RuntimeError: when expected search markup is missing or duplicated.
    """
    if not _HAS_SEARCH_MARKUP.search(output):
        return output

    for pattern, replacement, what in _TOGGLE_CONTROLS + _SCRIMS:
        output = _substitute_exactly_once(
            output, pattern, replacement, origin, what
        )

    # The scroll wrapper is rewritten by operating on its captured attribute
    # run rather than by substituting a whole tag, because this is the one
    # element whose attribute order and quoting the re-serialiser changes.
    # Demoting rather than deleting the tabindex keeps the element reachable
    # programmatically - the theme's own ArrowDown handling relies on it being
    # scrollable - while taking it out of sequential navigation.
    def demote_scrollwrap(match: Any) -> str:
        attributes = match.group(1)
        if _TABINDEX_ZERO.search(attributes):
            attributes = _TABINDEX_ZERO.sub('tabindex="-1"', attributes)
        elif "tabindex" not in attributes:
            attributes += ' tabindex="-1"'
        return "<div%s>" % attributes

    patched, count = _SEARCH_SCROLLWRAP.subn(demote_scrollwrap, output)
    if count == 0:
        raise RuntimeError(
            "%s renders the Material search field but its search scroll "
            "wrapper could not be found, so it cannot be corrected. The "
            "theme's markup has changed. Update the pattern in "
            "mkdocs_hooks.py. Failing the build deliberately: the alternative "
            "is publishing a focus stop that is zero-size, unnamed, and closes "
            "the panel it sits inside." % origin
        )
    if count > 1:
        raise RuntimeError(
            "%s contains %d search scroll wrappers; exactly one was expected."
            % (origin, count)
        )
    output = patched

    before = output
    output = _substitute_exactly_once(
        output, _SEARCH_META, _SEARCH_META_FIXED, origin, "search result count"
    )
    if output == before:
        raise RuntimeError(
            "%s renders the Material search field but its result-count "
            "element could not be found, so the count cannot be announced. "
            "The theme's markup has changed. Update the pattern in "
            "mkdocs_hooks.py." % origin
        )

    return output


def _hide_decorative_icons(output: str) -> str:
    """Mark the theme's icon glyphs as decorative.

    Deliberately count-based rather than fail-loud: the number of icons on a
    page is a property of its navigation position, not a contract, and a page
    with none is legitimate. The pattern's negative lookaheads make it a no-op
    on any icon that already carries a role or a name, so it cannot fight a
    future theme that labels its own.

    :param output: rendered HTML.
    :returns: the HTML with decorative icons hidden from assistive technology.
    """
    return _DECORATIVE_SVG.sub(r'<svg aria-hidden="true"\1>', output)


def _fix_footer_link_names(output: str) -> str:
    """Bring the footer links into WCAG 2.5.3 compliance.

    The visible text is "Next Project Guide" and the theme's accessible name
    was "Next: Project Guide"; a colon is enough to break the containment
    2.5.3 (Label in Name) requires.

    Deleting the colon was the first attempt and it *did* satisfy the
    criterion -- containment held on every footer link, measured. It did not
    silence axe, and the reason is worth recording because it looks like a
    remaining defect and is not one. The link's two visible parts are a
    ``span.md-footer__direction`` ("Next") and a ``div.md-ellipsis``
    ("Project Guide"); the second is block-level, so axe 4.12 extracts the
    visible text as ``"Next\\nProject Guide"`` with a newline and compares it
    against ``"Next Project Guide"`` with a space, without collapsing the
    whitespace first. axe 4.10.2 on the identical DOM passes. It is a
    checker behaviour change, not a markup fault -- but a red audit that has
    to be explained away in prose is worth less than one that is green, and
    every reader of the published gate evidence would have to be told this
    same story.

    So the attribute goes entirely, and the accessible name is computed from
    the content instead. That is the better answer independently of axe:

    - The name becomes the visible text by construction, so 2.5.3 cannot be
      violated by any later edit to either half.
    - Nothing is lost. The direction word is real visible text -- the theme
      styles it ``font-size:.64rem; opacity:.7`` and never hides it at any
      breakpoint -- so a screen reader still announces "Next Project Guide".
      This was checked against the built stylesheet rather than assumed,
      because the whole argument collapses if the word is display:none on
      small viewports.
    - The arrow ``<svg>`` alongside it is ``aria-hidden`` by
      :func:`_hide_decorative_icons`, so it contributes nothing to the
      computed name.

    Count-based, because the home page has one footer link and interior pages
    have two.

    :param output: rendered HTML.
    :returns: the HTML with the redundant footer link labels removed.
    """
    return _FOOTER_LINK_LABEL.sub("", output)


def _inject_diagram_guard(output: str, origin: str) -> str:
    """Put the pinned renderer guard, and the diagram names, before the bundle.

    Order is the whole point. The theme's loader short-circuits when
    ``window.mermaid`` is already defined, so the guard has to execute first or
    the floating ``mermaid@11`` tag is fetched before it can be prevented.
    ``extra_javascript`` is emitted after the bundle and therefore cannot do
    this; see the module docstring.

    The bundle's own relative prefix is reused rather than recomputed, so the
    injected ``src`` resolves identically at every nesting depth.

    :param output: rendered HTML.
    :param origin: how to refer to ``output`` in an error message.
    :returns: the HTML with the guard injected, or unchanged when the output
        carries no theme bundle at all.
    :raises RuntimeError: when more than one theme bundle is present.
    """
    matches = list(_THEME_BUNDLE.finditer(output))
    if not matches:
        # sitemap.xml, and any future template with no theme scripts.
        return output
    if len(matches) > 1:
        raise RuntimeError(
            "%s references %d theme bundles; exactly one was expected, so the "
            "injection point for the diagram renderer guard is ambiguous."
            % (origin, len(matches))
        )

    match = matches[0]
    # Works for the interior pages' "../assets/..." and for 404.html's
    # absolute "/assets/...", because both end at the same marker.
    prefix = match.group(1).rsplit("assets/", 1)[0]
    names = _diagram_names(output)

    injected = (
        '<script id="carddemo-diagram-names">window.__carddemoDiagramNames = '
        "%s;</script>" % json.dumps(names)
        + '<script src="%sjavascripts/carddemo-mermaid.js"></script>'
        % prefix
        + match.group(0)
    )
    return output[: match.start()] + injected + output[match.end() :]


def _apply_corrections(output: str, origin: str) -> str:
    """Run every build-time accessibility correction over one rendered output.

    Ordered so that each rewrite sees the markup it was measured against: the
    icon pass runs AFTER the toggle pass, because the toggles' own glyphs are
    among the icons it hides and the toggle patterns match the theme's exact
    attribute order.

    :param output: rendered HTML, either a documentation page or a template.
    :param origin: how to refer to ``output`` in an error message.
    :returns: the corrected HTML.
    """
    output = _name_search_dialog(output, origin)
    output = _correct_search_and_toggles(output, origin)
    output = _hide_decorative_icons(output)
    output = _fix_footer_link_names(output)
    output = _inject_diagram_guard(output, origin)
    return output


def on_post_page(output: str, page: Any, config: Any) -> str:
    """Apply the build-time accessibility corrections to a documentation page.

    :param output: the rendered HTML for one page.
    :param page: the MkDocs page, used only to name the file in an error.
    :param config: the MkDocs configuration, unused.
    :returns: the corrected HTML.
    """
    origin = getattr(getattr(page, "file", None), "src_uri", "<unknown page>")
    return _apply_corrections(output, origin)


def on_post_template(output: str, template_name: str, config: Any) -> str:
    """Name the search dialog on a rendered theme template.

    Documentation pages do not come through here; ``404.html`` and
    ``sitemap.xml`` do. ``404.html`` carries the full header and therefore the
    same unnamed dialog, so omitting this event would leave exactly one
    published page still failing the audit - the page a reader reaches by
    following a broken link, which is a poor place to regress.

    :param output: the rendered template output.
    :param template_name: the template's name, used in an error message.
    :param config: the MkDocs configuration, unused.
    :returns: the corrected output, or unchanged when the template has no
        search block and no theme bundle.
    """
    return _apply_corrections(output, "theme template %s" % template_name)


def on_env(env: Any, config: Any, files: Any) -> Any:
    """Set the search separator on the plugin that actually writes the index.

    This is the primary fix for the hyphen-tokenisation defect that
    :func:`on_post_build` documents in full; read that first for the measured
    behaviour and for why the value is what it is.

    WHY HERE AND NOT IN ``mkdocs.yml``, AND WHY NOT ONLY IN ``on_post_build``.
    Three layers were tried and the first two do not work:

    1. ``mkdocs.yml``. ``techdocs-core`` builds its own search plugin with
       ``search_plugin.load_config({})`` and assigns it over
       ``config["plugins"]["search"]``. The empty dict means no ``separator``
       declared in configuration can reach it. Measured: declaring
       ``material/search`` with a ``separator`` left the shipped index
       unchanged at ``[\\s\\-]+``.
    2. ``on_post_build`` alone. Material's search plugin writes
       ``search_index.json`` in its OWN ``on_post_build``, and because
       ``techdocs-core`` appends the ``search`` key late it runs last -
       measured, after this file's. So a rewrite there was performed, logged,
       and then immediately overwritten by the plugin. The log line said the
       correction had happened while the shipped file said otherwise, which is
       the most misleading possible outcome and is why this event exists.
    3. ``on_env``. Runs after every plugin's ``on_config`` - so after Material
       has filled in its own default - and before any ``on_post_build``. The
       plugin then writes the corrected value itself, which is the only place
       the change is single-sourced rather than raced.

    The rewrite in ``on_post_build`` is deliberately kept as well. The two are
    not redundant: whichever of the two ``on_post_build`` handlers runs last
    wins, and this event makes both of them agree, so the shipped file is
    correct under either ordering. If a future MkDocs reorders them, nothing
    here has to change.

    :param env: the Jinja environment, returned unmodified.
    :param config: the MkDocs configuration, whose plugin collection is read.
    :param files: the file collection, unused.
    :returns: ``env`` unchanged - this hook alters configuration, not templates.
    :raises RuntimeError: when a plugin is registered under ``search`` but
        exposes no ``separator`` option. As everywhere in this file, a
        correction that can no longer be applied fails the build rather than
        silently publishing the defect.
    """
    plugins = config["plugins"] if "plugins" in config else None
    if not plugins:
        return env

    search = plugins.get("search") or plugins.get("material/search")
    if search is None:
        # No search plugin active, so there is no index to tokenise.
        return env

    settings = getattr(search, "config", None)
    if settings is None or "separator" not in settings:
        raise RuntimeError(
            "the active search plugin (%s) exposes no 'separator' option, so "
            "hyphenated identifiers cannot be made searchable. The plugin's "
            "configuration schema has changed. Update on_env in "
            "mkdocs_hooks.py." % type(search).__name__
        )

    current = settings["separator"]
    if current != _SEARCH_SEPARATOR:
        settings["separator"] = _SEARCH_SEPARATOR
        log.info(
            "[carddemo] search separator %r -> %r on %s, so hyphenated "
            "identifiers are indexed and queried as single tokens.",
            current,
            _SEARCH_SEPARATOR,
            type(search).__name__,
        )

    # The plugin's own config is not what ends up in the shipped file, and
    # this is the fourth layer the correction had to be chased through. In
    # `on_config` the plugin does `self.search_index = SearchIndex(**self.config)`
    # - a **kwargs expansion, so the index holds a PLAIN DICT COPY taken at that
    # moment - and `generate_search_index` reads its separator from that copy,
    # not from the live plugin config. Setting only the plugin config above
    # therefore logged a correction, left the copy untouched, and shipped the
    # old value: measured, the build printed the change twice and the file still
    # read `[\s\-]+`. Both have to be set, and the copy is the one that decides.
    index = getattr(search, "search_index", None)
    index_config = getattr(index, "config", None)
    if isinstance(index_config, dict):
        if "separator" not in index_config:
            raise RuntimeError(
                "the active search plugin's index carries no 'separator' key, "
                "so hyphenated identifiers cannot be made searchable. The "
                "plugin's internals have changed. Update on_env in "
                "mkdocs_hooks.py."
            )
        index_config["separator"] = _SEARCH_SEPARATOR

    return env


#: The statement in the shipped search worker where Material applies the
#: configured separator to the INDEXING tokenizer. Written as a pattern rather
#: than a literal because the minified name of the config parameter is not
#: stable across theme builds; the property names are, since esbuild does not
#: mangle them.
_WORKER_SEPARATOR_APPLIED = re.compile(
    r'\blunr\.tokenizer\.separator\s*=\s*new RegExp\([^()]*\)'
)

#: Appended to it, so the query lexer re-reads the value Material just set.
#: Also serves as the idempotency marker.
_WORKER_QUERY_LEXER_FIX = (
    ",lunr.QueryLexer.termSeparator=lunr.tokenizer.separator"
)

#: Proof that the property this fix assigns is the one lunr's lexer reads. If
#: lunr ever stops capturing the separator at load, this disappears and the
#: fix is no longer needed.
_WORKER_LEXER_CAPTURE = re.compile(r'\.QueryLexer\.termSeparator\s*=')


def _correct_search_worker(site_dir: str) -> None:
    """Make the search worker split a QUERY the way it splits the INDEX.

    See :func:`on_post_build` for the measurement and the byte offsets that
    establish why this cannot be done from configuration. In short: lunr
    copies ``tokenizer.separator`` into ``QueryLexer.termSeparator`` when the
    module loads, and Material assigns the configured separator to
    ``tokenizer.separator`` afterwards, so the lexer keeps lunr's default
    ``/[\\s\\-]+/`` for the life of the page and every hyphenated query is
    split and ORed.

    The repair appends one comma-expression to the statement that applies the
    configured value, re-running the capture immediately after it. It is a
    comma-expression because the surrounding minified code is already a comma
    sequence, so no statement boundary is introduced and no brace matching is
    involved -- which is what makes a textual patch of minified output
    defensible here rather than reckless.

    Every failure mode fails the build. A theme upgrade that renames,
    reorders or removes any of the three anchors this depends on produces a
    hard error, because the alternative is publishing a search box that
    quietly cannot find identifiers -- the exact defect this exists to close,
    and one that no page renders any sign of.

    :param site_dir: the built site directory.
    :returns: nothing; the worker is rewritten in place.
    :raises RuntimeError: when the worker is present but does not have the
        shape this rewrite was written against.
    """
    pattern = os.path.join(
        site_dir, "assets", "javascripts", "workers", "search.*.min.js"
    )
    workers = sorted(glob.glob(pattern))
    if not workers:
        # Material's search worker is absent, so there is no client-side
        # query lexer and nothing to correct.
        return

    for worker_path in workers:
        with open(worker_path, encoding="utf-8") as handle:
            source = handle.read()

        if _WORKER_QUERY_LEXER_FIX in source:
            continue

        if not _WORKER_LEXER_CAPTURE.search(source):
            raise RuntimeError(
                "%s has no QueryLexer.termSeparator assignment, so query "
                "tokenisation can no longer be aligned with the index. Either "
                "lunr stopped capturing the separator at load - in which case "
                "_correct_search_worker in mkdocs_hooks.py is now redundant "
                "and should be deleted - or the worker's shape has changed. "
                "Verify which before removing this check." % worker_path
            )

        matches = _WORKER_SEPARATOR_APPLIED.findall(source)
        if len(matches) != 1:
            raise RuntimeError(
                "%s applies the configured separator to lunr's tokenizer %d "
                "times; exactly one was expected. Re-derive the anchor in "
                "_correct_search_worker in mkdocs_hooks.py before this build "
                "can publish a search box that finds hyphenated identifiers."
                % (worker_path, len(matches))
            )

        patched = _WORKER_SEPARATOR_APPLIED.sub(
            lambda match: match.group(0) + _WORKER_QUERY_LEXER_FIX,
            source,
            count=1,
        )
        with open(worker_path, "w", encoding="utf-8") as handle:
            handle.write(patched)

        log.info(
            "[carddemo] %s: query lexer now re-reads the configured "
            "separator, so a hyphenated identifier is one query term rather "
            "than three ORed ones.",
            worker_path,
        )


def on_post_build(config: Any) -> None:
    """Stop the search index treating a hyphen as a token separator.

    THE DEFECT. Searching the published site for ``CVE-2026-41716`` - an
    identifier that appears nowhere in the corpus - returned "5 matching
    documents" and 46 result links. The shipped index carries
    ``"separator": "[\\s\\-]+"``, so the hyphen splits that identifier into
    ``cve``, ``2026`` and ``41716``, and lunr ORs the three. Every result was
    a document containing the unremarkable token ``2026`` or ``cve``; the
    theme's own annotation said so on each one, reading ``Missing: 41716`` -
    the only distinctive part of the query matched nothing, on every hit.

    Worse than the noise is that it is indistinguishable from signal.
    ``CVE-2026-40973``, which DOES appear in the corpus, returned the same "5
    matching documents", the same 46 links and the same five documents in the
    same order, with the one section that actually contains it ranked fourth
    and collapsed out of sight. A reader cannot tell a real identifier from an
    absent one by the result. A hyphen-free control query for an equally
    absent token returned "No matching documents", which isolates the
    separator as the sole cause.

    WHY THIS IS DONE HERE, IN THE BUILT ARTEFACT, RATHER THAN IN CONFIGURATION.
    ``techdocs-core`` constructs its own search plugin with
    ``search_plugin.load_config({})`` and assigns it over ``config["plugins"]
    ["search"]`` during ``on_config``. Because it passes an empty
    configuration, no ``separator`` declared in ``mkdocs.yml`` can reach it,
    and because the key is added late it runs last and its output is the file
    that survives. Declaring ``material/search`` with a ``separator`` was
    measured and had no effect on the shipped index for exactly that reason.

    That leaves this file. Material's search runs entirely in the browser and
    builds its lunr index client-side from ``search_index.json``, reading
    ``config.separator`` to tokenise the documents.

    IT IS NOT ENOUGH ON ITS OWN, and an earlier revision of this docstring
    asserted that it was -- that "there is no second copy to keep in step".
    That claim was wrong and is withdrawn here rather than quietly edited
    out, because it is the kind of wrong that verifies green: re-tokenising a
    sample identifier against the shipped ``separator`` confirms the INDEX
    side is fixed, and stops one step short of the half that is not.

    Browser measurement after the index rewrite: ``CVE-2026-41716`` still
    returned "6 matching documents" and 49 links, marginally worse than the
    5/46 it started at, and the theme still annotated every hit
    ``Missing: 40973 cve`` for the identifier that does exist. The index and
    the query had been left disagreeing -- the index storing
    ``cve-2026-41716`` whole while the query was still split on the hyphen -
    which makes an exact identifier simultaneously un-matchable and noisy.

    The second copy is ``lunr.QueryLexer.termSeparator``, and byte offsets in
    the shipped worker show why configuration cannot reach it. In
    ``assets/javascripts/workers/search.<hash>.min.js``:

    - offset 4384: ``tokenizer.separator=/[\\s\\-]+/`` -- lunr's default.
    - offset 26084: ``QueryLexer.termSeparator=t.tokenizer.separator`` --
      lunr's own source, evaluated once at load, capturing a REFERENCE to
      that default RegExp object.
    - offset 36541: ``lunr.tokenizer.separator=new RegExp(e.separator)`` --
      Material applying the configured value, 10 KB of module later.

    The third statement rebinds the property to a new object; the second has
    already taken the old one. Nothing afterwards updates the lexer, and a
    RegExp's source cannot be mutated in place, so no value of ``separator``
    anywhere in any configuration file can change how a query is split. The
    only fix is to re-run the capture after Material has applied the config,
    which is what :func:`_correct_search_worker` does in one statement.

    This is a defect in the interaction between lunr and Material rather than
    in this project, and it is repaired in the build output because that is
    the only place this project owns. It restores the behaviour Material's own
    ``separator`` option documents -- index and query splitting words the
    same way -- rather than introducing behaviour of our own.

    THE VALUE. ``[\\s,:!=\\[\\]()"/]+`` is Material's own default with the
    hyphen removed and its CamelCase and decimal-point rules left out. The
    hyphen is load-bearing in nearly every identifier this corpus indexes -
    advisory ids, ``DL-RM-15``, ``spring-boot-starter-parent`` - while commas,
    colons and brackets are punctuation that should still split, so removing
    only the hyphen is both the minimal change and the right one.

    :param config: the MkDocs configuration, read for ``site_dir``.
    :returns: nothing; the index is rewritten in place.
    :raises RuntimeError: when the index exists but does not carry the
        separator this rewrite was written against. As everywhere else in this
        file, a rewrite that stops matching must fail the build rather than
        silently publish the defect it was added to remove.
    """
    site_dir = config["site_dir"] if "site_dir" in config else None
    if not site_dir:
        return

    # Both halves, always. The index rewrite below has several legitimate
    # early exits and the query-lexer repair must not be skipped by any of
    # them: half a fix here reads, from every automated check, exactly like a
    # whole one.
    _correct_search_worker(site_dir)

    index_path = os.path.join(site_dir, "search", "search_index.json")
    if not os.path.isfile(index_path):
        # No search plugin active, so there is no index and nothing to correct.
        return

    with open(index_path, encoding="utf-8") as handle:
        index = json.load(handle)

    settings = index.get("config")
    if not isinstance(settings, dict) or "separator" not in settings:
        raise RuntimeError(
            "%s carries no config.separator, so hyphenated identifiers cannot "
            "be made searchable. The search plugin's index format has changed. "
            "Update on_post_build in mkdocs_hooks.py." % index_path
        )

    current = settings["separator"]
    if current == _SEARCH_SEPARATOR:
        return

    if "\\-" not in current and "-" not in current:
        log.info(
            "[carddemo] %s already treats the hyphen as part of a token "
            "(separator %r); this rewrite is now redundant and can be removed.",
            index_path,
            current,
        )
        return

    settings["separator"] = _SEARCH_SEPARATOR
    with open(index_path, "w", encoding="utf-8") as handle:
        json.dump(index, handle, ensure_ascii=False, separators=(",", ":"))

    log.info(
        "[carddemo] %s: search separator %r -> %r, so hyphenated identifiers "
        "are indexed and queried as single tokens.",
        index_path,
        current,
        _SEARCH_SEPARATOR,
    )
