# Cooking Blog interface system

## Direction

The browser should feel like a well-used kitchen notebook: warm, calm, and
quick to operate with a thumb while cooking. Recipes and cooking history are
the focal content; actions are quiet annotation tools grouped with the item
they change.

Meal photos are the visual record of each cooking attempt. They should win the
hierarchy through size, aspect ratio, and spacing. The signature state is a
filled herb-green Primary badge over the selected food photo, paired with a
compact labeled tool dock.

## Foundation

- Use the parchment/ink/herb/terracotta token family in `app-v1.css` rather
  than introducing neutral gray or unrelated accent colors. Herb green marks
  the affirmative primary-photo state; danger color is reserved for deletion.
- Keep the existing subtle-card depth strategy: quiet `--line` borders with
  restrained warm shadows. Overflow menus sit one surface above their card
  without becoming heavy floating panels.
- Use serif headings and meal dates for recipe-like content. Use the rounded
  sans-serif UI typeface for captions, controls, and supporting copy.
- Maintain the 4px spacing rhythm. Cards use 16px padding, tightly related
  tools use 6–8px gaps, and caption docks use about 12px of separation from
  the image and surrounding content.
- Preserve the hierarchy: photo first, meal metadata and caption second,
  management controls third.

## Responsive composition

- Render meal photos as a one-column gallery on phones and an auto-fitting
  two-column gallery on wider screens. Use the display variant in a stable 4:3
  frame with `object-fit: cover`; do not return to database-style thumbnails.
- Keep photo figures quiet: one warm surface groups the image, caption, and
  tools without adding another heavy card inside each meal.
- At phone widths, title and content columns use `min-width: 0`, long tokens
  wrap, and detail-page action trays move below long titles. No action group may
  create horizontal page overflow.
- All interactive targets are at least 44 by 44 pixels even when their visible
  glyph or text is smaller.

## Reusable patterns

- `icon-action`: use a native link or button with visible keyboard focus and a
  44px minimum hit area. Ambiguous actions use an icon plus a short persistent
  label in a compact pill. Icon-only treatment is limited to conventional
  actions whose meaning is explicit from page context. Keep an `aria-label`
  and an `aria-hidden` glyph; `title` is supplemental, never the explanation.
- `action-tray`: a wrapping group of compact actions aligned with the heading
  or item it changes. Keep the group visually subordinate and associated with
  its target.
- Photo action dock: place the labeled actions below the caption using the
  terms `Primary` or `Make primary`, `Edit caption`, `Download`, and `More`.
  The dock may gain contrast on hover or `:focus-within`, but its meaning and
  targets remain visible on touch devices.
- Primary-photo state: overlay a persistent `Primary` badge near the upper-left
  of the image. The effective primary—explicit selection or deterministic
  fallback—uses the filled herb star, `aria-pressed="true"`, and a disabled
  redundant selection action. Other photos use an outlined star and
  `Make primary`.
- Read-first caption: render the caption as ordinary text, or a quiet
  `No caption` placeholder. A native `details`/`summary` labeled
  `Edit caption` reveals the prepopulated input, validation output, and a
  visibly labeled `Save caption` action. Focus the editor when opened; render
  the saved text with the disclosure closed after redirect.
- Rare editor disclosure: keep infrequent forms such as add-source behind a
  compact native disclosure while leaving current source status visible.
- Overflow menu: use native `details`/`summary` for `More`. Menu contents are
  full-width rows with icons and visible text, such as `Delete photo`, rather
  than circular mystery controls. Bound the panel to
  `max-width: calc(100vw - 2rem)` and allow labels to wrap.
- Collision-safe placement: the small browser asset may add upward-opening or
  start-aligned classes after measuring the trigger and panel. Recalculate on
  resize, close on scroll or outside interaction, close on Escape, and return
  focus to the trigger. Cards containing menus must not clip overflow.
- Permanent deletion appears as a labeled danger row in overflow where space
  is constrained and always retains the existing confirmation behavior.
- `compact-editor` remains appropriate for source metadata and similar inline
  edits, but its save action must have visible meaning; do not use an
  unexplained icon-only submit control.

## Accessibility and interaction contract

- Prefer native links, buttons, forms, inputs, and `details` over custom
  interactive containers.
- Every action has a unique programmatic name, persistent visible meaning when
  ambiguous, and a non-overlapping 44px minimum target. Discovery must not
  depend on hover or a tooltip.
- Preserve visible `:focus-visible` treatment, keyboard activation, Escape and
  focus-return behavior, error association, invalid-field focus, and reduced
  motion.
- Express selected state semantically with `aria-pressed`; do not rely on color
  or the hero image alone to communicate which photo is primary.

## Visual references and verification

- `docs/ui/mobile-photo-actions-concept.png` establishes the labeled photo
  tool dock and photo-first action hierarchy.
- `docs/ui/mobile-recipe-detail-concept.png` establishes the phone composition:
  prominent food imagery, readable meal metadata, and controls that support
  rather than compete with the journal content.
- Verify photo hierarchy and interaction at 390x844, 768x1024, and desktop
  viewports. Check menu bounds, touch targets, caption disclosure, primary
  state, focus behavior, and horizontal overflow—not image visibility alone.
