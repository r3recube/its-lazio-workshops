const API = {
  bikes:   "http://localhost:8081/api/bikes",
  cars:    "http://localhost:8082/api/cars",
  garages: "http://localhost:8083/api/garages",
};

async function fetchAll(resource) {
  const r = await fetch(API[resource]);
  return r.json();
}

async function create(resource, payload) {
  const r = await fetch(API[resource], {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!r.ok) throw new Error("Errore " + r.status);
  return r.json();
}

async function remove(resource, id) {
  const r = await fetch(`${API[resource]}/${id}`, { method: "DELETE" });
  if (!r.ok) throw new Error("Errore " + r.status);
}

function rowFor(resource, item) {
  const tr = document.createElement("tr");
  const fields = Object.keys(item).filter(k => k !== "id" && k !== "vehicles");
  tr.innerHTML = `<td>${item.id}</td>` +
    fields.map(f => `<td>${item[f] ?? ""}</td>`).join("") +
    `<td><button class="btn btn-sm btn-danger" data-id="${item.id}">🗑</button></td>`;
  tr.querySelector("button").onclick = async () => {
    await remove(resource, item.id);
    tr.remove();
  };
  return tr;
}

async function renderList(resource, tableId, formId, errorId) {
  const tbody = document.getElementById(tableId);
  const form  = document.getElementById(formId);
  const errorEl = errorId ? document.getElementById(errorId) : null;

  async function refresh() {
    tbody.innerHTML = "";
    const items = await fetchAll(resource);
    items.forEach(i => tbody.appendChild(rowFor(resource, i)));
  }

  form.onsubmit = async (e) => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(form).entries());
    ["engineCc", "year", "price", "doors", "seats"].forEach(k => {
      if (data[k] !== undefined) data[k] = Number(data[k]);
    });
    try {
      if (errorEl) errorEl.classList.add('d-none');
      await create(resource, data);
      form.reset();
      refresh();
    } catch (err) {
      if (errorEl) {
        errorEl.textContent = err.message;
        errorEl.classList.remove('d-none');
      }
    }
  };

  refresh();
}
