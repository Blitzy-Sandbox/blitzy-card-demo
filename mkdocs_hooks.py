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
  runs once per page at build time, adds nothing to the runtime, and needs no
  JavaScript - the published pages carry exactly one script, the theme's own
  bundle, and that stays true.

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

import logging
import re
from typing import Any

log = logging.getLogger("mkdocs.hooks.carddemo")

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


def on_post_page(output: str, page: Any, config: Any) -> str:
    """Name the search dialog on a rendered documentation page.

    :param output: the rendered HTML for one page.
    :param page: the MkDocs page, used only to name the file in an error.
    :param config: the MkDocs configuration, unused.
    :returns: the HTML with the search dialog named.
    """
    origin = getattr(getattr(page, "file", None), "src_uri", "<unknown page>")
    return _name_search_dialog(output, origin)


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
    :returns: the output with the search dialog named, or unchanged when the
        template has no search block.
    """
    return _name_search_dialog(output, "theme template %s" % template_name)
