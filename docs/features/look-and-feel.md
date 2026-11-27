# Look and feel

The side menu, the three themes and the motion.

[← Back to the README](../../README.md) · [All features](../FEATURES.txt)

- **Side menu.** A collapsible sidebar down the left side. Open, it lists each action with an icon and a label, **nested under five parent items**; point at a parent and its items open in a flyout beside it (tab into it, or click it on a touch screen, to do the same): Organiser (Reminders, Notes), Categories (Edit / Add categories), Themes (Light, Dark, Color), Data (Backups, Exchange rates, Export to Excel) and Account (Screen lock, Change password). The ☰ button at its top folds it to a narrow bar that shows only the ☰ button, and opens it again; the choice is kept in the browser, and on a window narrower than 900 pixels it starts folded. The page makes room for it, and choosing an item does not fold it.

- **Color theme.** Under Themes, three buttons pick **Light**, **Dark** or **Color** (a bright theme with a teal accent and a mint-to-peach page). The choice is kept in the browser (`localStorage["theme"]`); until you choose, Light or Dark follows the computer.

- **Dark theme.** The **Dark** item in the menu switches to it. The choice is kept in the browser; until you
  make one the page follows your computer's setting, and it is applied before anything is drawn, so there is no flash.
  The chart colours have their own dark steps, checked for contrast and colour-blind separation.

- **Motion.** Dialogs and the screen lock fade in, buttons and tiles give a little when pressed, new rows and tiles rise
  in once (a redraw does not replay it), the Stats rings draw a thin circle first and then swell out into their categories every time you open Stats or change its period, and the net worth figures count up when you show them
  or the data changes (hidden amounts only ever show the mask). Switching view settles the new view in after the card
  turns, the calendar slides the way you move months, the sidebar's groups fold, a bar whose value changed slides to its
  new width, a saved transaction lights up briefly and a deleted one folds away. If your computer is set to reduce motion, none of it moves.
