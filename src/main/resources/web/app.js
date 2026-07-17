/*
 * Trading desk shell controller. The desk owns the chrome and switches tabs; each tab is a
 * service's own front end in an iframe (served through the gateway with ?embed=1), so all the
 * live rendering stays in the embedded apps. All three frames stay mounted and only visibility
 * toggles, so a tab's SSE stream keeps running while you're looking at another.
 */
(function () {
  "use strict";

  var TABS = ["orderbook", "risk", "trading"];
  var LABELS = { orderbook: "order book", risk: "risk", trading: "trading" };
  var STORAGE_KEY = "desk.tab";

  var tabs = Array.prototype.slice.call(document.querySelectorAll(".tab"));
  var frames = Array.prototype.slice.call(document.querySelectorAll(".frame"));
  var activeLabel = document.getElementById("active-label");

  function activate(name) {
    if (TABS.indexOf(name) === -1) return;
    tabs.forEach(function (t) {
      var on = t.dataset.tab === name;
      t.classList.toggle("active", on);
      t.setAttribute("aria-selected", on ? "true" : "false");
    });
    frames.forEach(function (f) {
      f.classList.toggle("active", f.dataset.frame === name);
    });
    if (activeLabel) activeLabel.textContent = LABELS[name];
    try {
      localStorage.setItem(STORAGE_KEY, name);
      var url = new URL(window.location.href);
      url.searchParams.set("tab", name);
      window.history.replaceState(null, "", url);
    } catch {
      /* storage or history unavailable — tab still switches */
    }
  }

  tabs.forEach(function (t) {
    t.addEventListener("click", function () {
      activate(t.dataset.tab);
    });
  });

  // Number keys switch tabs while the chrome has focus. Keystrokes inside a tab stay with that
  // app (iframe events don't reach the parent), which is what a trader typing an order wants.
  document.addEventListener("keydown", function (e) {
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    var index = ["1", "2", "3"].indexOf(e.key);
    if (index !== -1) activate(TABS[index]);
  });

  // The chrome's connection light goes live once the first tab has loaded through the gateway.
  var connected = false;
  function markLive() {
    if (connected) return;
    connected = true;
    ["conn", "conn2"].forEach(function (id) {
      var dot = document.getElementById(id);
      if (dot) dot.classList.add("live");
    });
    ["connlbl", "connlbl2"].forEach(function (id) {
      var label = document.getElementById(id);
      if (label) label.textContent = "live";
    });
  }
  frames.forEach(function (f) {
    f.addEventListener("load", markLive);
  });

  // Desk clock, HH:MM:SS local time.
  var clock = document.getElementById("clock");
  function tick() {
    var d = new Date();
    var p = function (n) {
      return String(n).padStart(2, "0");
    };
    clock.textContent =
      p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }
  tick();
  setInterval(tick, 1000);

  // Restore the last tab: ?tab= wins, then the stored choice, then the default (order book).
  var requested = null;
  try {
    requested =
      new URL(window.location.href).searchParams.get("tab") ||
      localStorage.getItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
  if (requested && requested !== "orderbook") activate(requested);
})();
