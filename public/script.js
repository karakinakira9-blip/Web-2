"use strict";

const API_URL = "/fcgi-bin/lab2.jar";
const X_VALUES = [-5, -4, -3, -2, -1, 0, 1, 2, 3];
const Y_MIN = -3;
const Y_MAX = 3;
const R_MIN = 1;
const R_MAX = 4;

const form = document.getElementById("point_form");
const xGroup = document.getElementById("x_group");
const yInput = document.getElementById("y_input");
const rInput = document.getElementById("r_input");
const xError = document.getElementById("x_error");
const yError = document.getElementById("y_error");
const rError = document.getElementById("r_error");
const submitButton = document.getElementById("submit_btn");
const clearButton = document.getElementById("clear_btn");
const requestStatus = document.getElementById("request_status");
const resultBody = document.getElementById("result_body");
const emptyHint = document.getElementById("empty_hint");
const canvas = document.getElementById("plane");
const ctx = canvas.getContext("2d");

const selectedX = new Set();
let records = [];

function parseDecimal(raw) {
  const normalized = raw.trim().replace(",", ".");
  if (!/^-?(?:\d+(?:\.\d*)?|\.\d+)$/.test(normalized)) return null;

  const [whole, fraction = ""] = normalized.split(".");
  const significantFraction = fraction.replace(/0+$/, "");
  const digits = whole.replace("-", "") + significantFraction;
  const magnitude = BigInt(digits || "0");

  return {
    raw,
    normalized,
    coefficient: normalized.startsWith("-") ? -magnitude : magnitude,
    scale: significantFraction.length
  };
}

function inRange(value, min, max) {
  const factor = 10n ** BigInt(value.scale);
  return value.coefficient >= BigInt(min) * factor
    && value.coefficient <= BigInt(max) * factor;
}

X_VALUES.forEach((value) => {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = String(value);
  button.dataset.value = String(value);
  button.setAttribute("aria-pressed", "false");
  button.addEventListener("click", () => {
    if (selectedX.has(value)) {
      selectedX.delete(value);
    } else {
      selectedX.add(value);
    }
    const isSelected = selectedX.has(value);
    button.classList.toggle("active", isSelected);
    button.setAttribute("aria-pressed", String(isSelected));
    xError.textContent = "";
  });
  xGroup.appendChild(button);
});

function validateX() {
  if (selectedX.size === 0) {
    xError.textContent = "Выберите хотя бы одно значение X";
    return null;
  }
  xError.textContent = "";
  return X_VALUES.filter((value) => selectedX.has(value));
}

function validateTextInput(input, error, min, max, name) {
  const value = parseDecimal(input.value);
  if (value === null) {
    input.classList.add("invalid");
    error.textContent = `${name} должен быть числом`;
    return null;
  }
  if (!inRange(value, min, max)) {
    input.classList.add("invalid");
    error.textContent = `${name} должен быть в диапазоне [${min}; ${max}]`;
    return null;
  }
  input.classList.remove("invalid");
  error.textContent = "";
  return value;
}

function validateAll() {
  const x = validateX();
  const y = validateTextInput(yInput, yError, Y_MIN, Y_MAX, "Y");
  const r = validateTextInput(rInput, rError, R_MIN, R_MAX, "R");
  return { x, y, r, ok: x !== null && y !== null && r !== null };
}

yInput.addEventListener("input", () => {
  if (yInput.value.trim() === "") {
    yInput.classList.remove("invalid");
    yError.textContent = "";
  } else {
    validateTextInput(yInput, yError, Y_MIN, Y_MAX, "Y");
  }
});

rInput.addEventListener("input", () => {
  if (rInput.value.trim() === "") {
    rInput.classList.remove("invalid");
    rError.textContent = "";
    return;
  }
  const value = validateTextInput(rInput, rError, R_MIN, R_MAX, "R");
  if (value !== null) drawScene(value, records);
});

function setBusy(busy) {
  submitButton.disabled = busy;
  clearButton.disabled = busy;
}

function setStatus(message, type = "") {
  requestStatus.textContent = message;
  requestStatus.className = type;
}

async function postForm(params) {
  const response = await fetch(API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
      "Accept": "application/json"
    },
    body: params.toString(),
    cache: "no-store"
  });

  let data;
  try {
    data = await response.json();
  } catch (_error) {
    throw new Error("Сервер вернул некорректный JSON");
  }

  if (!response.ok || data.status !== "ok") {
    throw new Error(data.message || `Ошибка HTTP ${response.status}`);
  }
  return data;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const values = validateAll();
  if (!values.ok) return;

  const params = new URLSearchParams({
    action: "check",
    y: values.y.raw,
    r: values.r.raw
  });
  values.x.forEach((value) => params.append("x", String(value)));

  setBusy(true);
  setStatus("Отправка POST-запроса...");
  try {
    const data = await postForm(params);
    renderResults(data.history);
    drawScene(currentR(), records);
    setStatus("Результат получен", "success");
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    setBusy(false);
  }
});

clearButton.addEventListener("click", async () => {
  setBusy(true);
  setStatus("Очистка истории...");
  try {
    const data = await postForm(new URLSearchParams({ action: "clear" }));
    renderResults(data.history);
    drawScene(currentR(), records);
    setStatus("История очищена", "success");
  } catch (error) {
    setStatus(error.message, "error");
  } finally {
    setBusy(false);
  }
});

async function loadHistory() {
  setStatus("Загрузка истории...");
  try {
    const data = await postForm(new URLSearchParams({ action: "history" }));
    renderResults(data.history);
    drawScene(currentR(), records);
    setStatus("");
  } catch (error) {
    setStatus("Не удалось загрузить историю: " + error.message, "error");
  }
}

function formatServerTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  }).format(date);
}

function renderResults(history) {
  records = Array.isArray(history) ? history : [];
  resultBody.innerHTML = "";
  emptyHint.style.display = records.length ? "none" : "block";

  [...records].reverse().forEach((record, index) => {
    const row = document.createElement("tr");
    const cells = [
      records.length - index,
      record.x,
      record.y,
      record.r,
      record.hit ? "Попадает" : "Не попадает",
      formatServerTime(record.serverTime),
      (Number(record.executionTimeNs) / 1_000_000).toFixed(3) + " мс"
    ];

    cells.forEach((value, cellIndex) => {
      const cell = document.createElement("td");
      cell.textContent = String(value);
      if (cellIndex === 4) cell.classList.add(record.hit ? "hit" : "miss");
      row.appendChild(cell);
    });
    resultBody.appendChild(row);
  });
}

const WORLD_MIN = -5;
const WORLD_MAX = 5;
const PADDING = 34;

function toPx(x) {
  const scale = (canvas.width - 2 * PADDING) / (WORLD_MAX - WORLD_MIN);
  return PADDING + (x - WORLD_MIN) * scale;
}

function toPy(y) {
  const scale = (canvas.height - 2 * PADDING) / (WORLD_MAX - WORLD_MIN);
  return canvas.height - PADDING - (y - WORLD_MIN) * scale;
}

function currentR() {
  const value = parseDecimal(rInput.value);
  return value !== null && inRange(value, R_MIN, R_MAX) ? value : parseDecimal("2");
}

function drawScene(radius, points) {
  // Canvas принимает числа, а выбор точек по R остаётся точным.
  const r = Number(radius.normalized);
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  drawGrid();
  drawRegion(r);
  drawAxes(r);
  points.filter((point) => {
    const pointRadius = parseDecimal(String(point.r));
    return pointRadius !== null
      && pointRadius.coefficient === radius.coefficient
      && pointRadius.scale === radius.scale;
  }).forEach((point) => {
    const y = Number(String(point.y).trim().replace(",", "."));
    drawPoint(Number(point.x), y, point.hit);
  });
}

function drawGrid() {
  ctx.strokeStyle = "rgba(100, 100, 100, .14)";
  ctx.lineWidth = 1;
  for (let value = WORLD_MIN; value <= WORLD_MAX; value += 1) {
    ctx.beginPath();
    ctx.moveTo(toPx(value), toPy(WORLD_MIN));
    ctx.lineTo(toPx(value), toPy(WORLD_MAX));
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(toPx(WORLD_MIN), toPy(value));
    ctx.lineTo(toPx(WORLD_MAX), toPy(value));
    ctx.stroke();
  }
}

function drawRegion(r) {
  ctx.fillStyle = "rgba(255, 20, 147, .48)";
  ctx.strokeStyle = "#ff1493";
  ctx.lineWidth = 2;

  // III четверть: прямоугольник [-R; 0] x [-R/2; 0].
  ctx.beginPath();
  ctx.rect(toPx(-r), toPy(0), toPx(0) - toPx(-r), toPy(-r / 2) - toPy(0));
  ctx.fill();
  ctx.stroke();

  // II четверть: треугольник (-R/2, 0), (0, 0), (0, R/2).
  ctx.beginPath();
  ctx.moveTo(toPx(-r / 2), toPy(0));
  ctx.lineTo(toPx(0), toPy(0));
  ctx.lineTo(toPx(0), toPy(r / 2));
  ctx.closePath();
  ctx.fill();
  ctx.stroke();

  // I четверть: четверть круга радиуса R.
  ctx.beginPath();
  ctx.moveTo(toPx(0), toPy(0));
  ctx.lineTo(toPx(0), toPy(r));
  const steps = 80;
  for (let index = 0; index <= steps; index += 1) {
    const angle = Math.PI / 2 * (1 - index / steps);
    ctx.lineTo(toPx(r * Math.cos(angle)), toPy(r * Math.sin(angle)));
  }
  ctx.closePath();
  ctx.fill();
  ctx.stroke();
}

function drawAxes(r) {
  ctx.strokeStyle = "#222";
  ctx.fillStyle = "#222";
  ctx.lineWidth = 1.5;
  ctx.font = "12px Arial, sans-serif";

  ctx.beginPath();
  ctx.moveTo(toPx(WORLD_MIN), toPy(0));
  ctx.lineTo(toPx(WORLD_MAX), toPy(0));
  ctx.moveTo(toPx(0), toPy(WORLD_MIN));
  ctx.lineTo(toPx(0), toPy(WORLD_MAX));
  ctx.stroke();

  ctx.beginPath();
  ctx.moveTo(toPx(WORLD_MAX), toPy(0));
  ctx.lineTo(toPx(WORLD_MAX) - 10, toPy(0) - 5);
  ctx.lineTo(toPx(WORLD_MAX) - 10, toPy(0) + 5);
  ctx.closePath();
  ctx.fill();

  ctx.beginPath();
  ctx.moveTo(toPx(0), toPy(WORLD_MAX));
  ctx.lineTo(toPx(0) - 5, toPy(WORLD_MAX) + 10);
  ctx.lineTo(toPx(0) + 5, toPy(WORLD_MAX) + 10);
  ctx.closePath();
  ctx.fill();

  ctx.fillText("x", toPx(WORLD_MAX) - 18, toPy(0) - 9);
  ctx.fillText("y", toPx(0) + 8, toPy(WORLD_MAX) + 18);

  const marks = [
    { value: -r, label: "-R" },
    { value: -r / 2, label: "-R/2" },
    { value: r / 2, label: "R/2" },
    { value: r, label: "R" }
  ];
  marks.forEach(({ value, label }) => {
    ctx.beginPath();
    ctx.moveTo(toPx(value), toPy(0) - 4);
    ctx.lineTo(toPx(value), toPy(0) + 4);
    ctx.moveTo(toPx(0) - 4, toPy(value));
    ctx.lineTo(toPx(0) + 4, toPy(value));
    ctx.stroke();
    ctx.fillText(label, toPx(value) - 12, toPy(0) + 18);
    ctx.fillText(label, toPx(0) + 7, toPy(value) + 4);
  });
}

function drawPoint(x, y, hit) {
  ctx.beginPath();
  ctx.arc(toPx(x), toPy(y), 6, 0, Math.PI * 2);
  ctx.fillStyle = hit ? "#1a7f37" : "#c62828";
  ctx.fill();
  ctx.strokeStyle = "#fff";
  ctx.lineWidth = 2;
  ctx.stroke();
}

renderResults([]);
drawScene(currentR(), []);
loadHistory();
