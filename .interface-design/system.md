# Cooking Blog interface system

## Direction

The browser should feel like a well-used kitchen notebook: warm, calm, and
quick to operate with a thumb while cooking. Recipes and cooking history are
the focal content; actions are compact tools grouped with the item they change.

## Foundation

- Use the parchment/ink/herb/terracotta token family in `app-v1.css` rather
  than introducing neutral gray or unrelated accent colors.
- Keep the existing subtle-card depth strategy: quiet `--line` borders with
  restrained warm shadows, not heavy outlines or elevated floating surfaces.
- Use the existing serif headings for recipe-like content and the rounded
  sans-serif UI typeface for controls and supporting copy.
- Maintain the 4px spacing rhythm. Cards use 16px padding; closely related
  controls use 6px gaps; sections have visibly more breathing room.

## Reusable patterns

- `icon-action`: native link or button, circular, 44 by 44 pixels minimum,
  with visible keyboard focus, an `aria-label`, a `title`, and an
  `aria-hidden` glyph. Terracotta is reserved for the primary action.
- `action-tray`: a compact wrapping group of `icon-action` controls aligned
  with the heading or photo it changes. Do not put item actions in detached,
  wordy rows.
- `compact-editor`: an associated text field plus icon-only save control.
  Keep an explicit visible or screen-reader label and place form errors below
  the editor.
- Permanent deletion remains an icon action, but always uses the existing
  confirmation-form behavior before submission.
- On phone widths, title/content columns must have `min-width: 0` and
  headings must wrap long tokens so action trays never cause horizontal scroll.

## Accessibility

- Prefer native links, buttons, forms, inputs, and `details` over custom
  interactive containers.
- Every icon-only control must have a unique programmatic name; avoid labels
  that ambiguously match adjacent controls.
- Preserve the global visible `:focus-visible` treatment and reduced-motion
  behavior.
