/* "Reload everything and redraw" without the panels importing app.js (which imports them). A panel calls refreshData()
   after a change; app.js says what that means by calling setRefreshHandler once, when the app starts. */

let handler = async () => {};

export function setRefreshHandler(fn) { handler = fn; }

export function refreshData() { return handler(); }
