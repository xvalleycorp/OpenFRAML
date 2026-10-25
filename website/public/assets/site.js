// Closes an open language menu on a click outside it, or on Escape.
document.addEventListener("click", (event) => {
  for (const menu of document.querySelectorAll("details.lang-menu[open]")) {
    if (!menu.contains(event.target)) menu.open = false;
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  for (const menu of document.querySelectorAll("details.lang-menu[open]")) {
    menu.open = false;
    menu.querySelector("summary").focus();
  }
});
