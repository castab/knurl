(() => {
  "use strict";

  const PAGE_SIZE = 48;

  const els = {
    accountId: document.getElementById("accountId"),
    apiBearerToken: document.getElementById("apiBearerToken"),
    adminToken: document.getElementById("adminToken"),
    tabButtons: document.querySelectorAll(".tab-button"),
    tabPanels: document.querySelectorAll(".tab-panel"),

    gallerySort: document.getElementById("gallerySort"),
    galleryLimit: document.getElementById("galleryLimit"),
    galleryLoad: document.getElementById("galleryLoad"),
    galleryError: document.getElementById("galleryError"),
    galleryStatus: document.getElementById("galleryStatus"),
    galleryGrid: document.getElementById("galleryGrid"),

    filterSelected: document.getElementById("filterSelected"),
    filterPending: document.getElementById("filterPending"),
    filterImage: document.getElementById("filterImage"),
    filterCarousel: document.getElementById("filterCarousel"),
    filterVideo: document.getElementById("filterVideo"),
    catalogShowNotDigestible: document.getElementById("catalogShowNotDigestible"),
    catalogLoad: document.getElementById("catalogLoad"),
    catalogReset: document.getElementById("catalogReset"),
    catalogCommit: document.getElementById("catalogCommit"),
    pendingSummary: document.getElementById("pendingSummary"),
    catalogError: document.getElementById("catalogError"),
    catalogStatus: document.getElementById("catalogStatus"),
    catalogGrid: document.getElementById("catalogGrid"),
    catalogPagination: document.getElementById("catalogPagination"),
    catalogPrev: document.getElementById("catalogPrev"),
    catalogNext: document.getElementById("catalogNext"),
    catalogPageLabel: document.getElementById("catalogPageLabel"),

    lightbox: document.getElementById("lightbox"),
    lightboxImage: document.getElementById("lightboxImage"),
    lightboxVideo: document.getElementById("lightboxVideo"),
    lightboxCounter: document.getElementById("lightboxCounter"),
    lightboxCaption: document.getElementById("lightboxCaption"),
    lightboxClose: document.getElementById("lightboxClose"),
    lightboxPrev: document.getElementById("lightboxPrev"),
    lightboxNext: document.getElementById("lightboxNext"),
  };

  // ---- config persistence ----------------------------------------------

  const CONFIG_KEYS = {
    accountId: "knurl.accountId",
    apiBearerToken: "knurl.apiBearerToken",
    adminToken: "knurl.adminToken",
  };

  // The account id is not a secret and is persisted across browser sessions for convenience. The two
  // tokens are secrets and go to sessionStorage instead, so they do not survive the tab being closed.
  const SECRET_FIELDS = new Set(["apiBearerToken", "adminToken"]);

  for (const [field, key] of Object.entries(CONFIG_KEYS)) {
    const store = SECRET_FIELDS.has(field) ? sessionStorage : localStorage;
    const saved = store.getItem(key);
    if (saved !== null) els[field].value = saved;
    els[field].addEventListener("input", () => {
      store.setItem(key, els[field].value);
    });
  }

  // One-time cleanup: remove secrets written to localStorage by an earlier build of this page.
  for (const field of SECRET_FIELDS) localStorage.removeItem(CONFIG_KEYS[field]);

  function accountId() {
    return els.accountId.value.trim();
  }

  // ---- tabs ---------------------------------------------------------------

  els.tabButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      els.tabButtons.forEach((b) => b.classList.toggle("active", b === btn));
      const target = btn.dataset.tab;
      els.tabPanels.forEach((panel) => {
        panel.classList.toggle("active", panel.id === `tab-${target}`);
      });
    });
  });

  // ---- shared helpers -------------------------------------------------

  // Escapes for BOTH text and quoted-attribute contexts. The textContent/innerHTML round-trip used
  // previously escaped only & < >, because HTML text-node serialization leaves quotes alone - which is
  // unsafe here since every call site below interpolates into a double-quoted attribute.
  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  }

  function formatDate(iso) {
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return iso;
    }
  }

  async function apiFetch(path, { method = "GET", token, body } = {}) {
    const headers = {};
    if (token) headers["Authorization"] = `Bearer ${token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    let payload = null;
    try {
      payload = await response.json();
    } catch {
      // no/invalid JSON body
    }
    if (!response.ok) {
      const message = payload?.error ?? `HTTP ${response.status}`;
      throw new Error(`${message} (status ${response.status})`);
    }
    return payload;
  }

  function showError(el, message) {
    el.textContent = message;
    el.classList.toggle("hidden", !message);
  }

  // ---- Lightbox (full image / video view) -------------------------------

  let lightboxMediaItems = [];
  let lightboxIndex = 0;

  function openLightbox(mediaItems, captionText, startIndex = 0) {
    lightboxMediaItems = mediaItems;
    lightboxIndex = startIndex;
    els.lightboxCaption.textContent = captionText ?? "";
    renderLightboxMedia();
    els.lightbox.classList.remove("hidden");
  }

  function closeLightbox() {
    els.lightbox.classList.add("hidden");
    els.lightboxVideo.pause();
    els.lightboxVideo.removeAttribute("src");
    els.lightboxVideo.load();
  }

  function renderLightboxMedia() {
    const item = lightboxMediaItems[lightboxIndex];
    const isVideo = Boolean(item.videoUrl);
    els.lightboxImage.classList.toggle("hidden", isVideo);
    els.lightboxVideo.classList.toggle("hidden", !isVideo);
    if (isVideo) {
      els.lightboxImage.removeAttribute("src");
      els.lightboxVideo.src = item.videoUrl;
      els.lightboxVideo.load();
    } else {
      els.lightboxVideo.pause();
      els.lightboxVideo.removeAttribute("src");
      els.lightboxImage.src = item.largeUrl;
    }
    const multi = lightboxMediaItems.length > 1;
    els.lightboxCounter.textContent = multi
      ? `${lightboxIndex + 1} / ${lightboxMediaItems.length}`
      : "";
    els.lightboxPrev.classList.toggle("hidden", !multi);
    els.lightboxNext.classList.toggle("hidden", !multi);
  }

  function showLightboxOffset(offset) {
    lightboxIndex = (lightboxIndex + offset + lightboxMediaItems.length) % lightboxMediaItems.length;
    renderLightboxMedia();
  }

  els.lightboxClose.addEventListener("click", closeLightbox);
  els.lightboxPrev.addEventListener("click", () => showLightboxOffset(-1));
  els.lightboxNext.addEventListener("click", () => showLightboxOffset(1));
  els.lightbox.addEventListener("click", (event) => {
    if (event.target === els.lightbox) closeLightbox();
  });
  document.addEventListener("keydown", (event) => {
    if (els.lightbox.classList.contains("hidden")) return;
    if (event.key === "Escape") closeLightbox();
    else if (event.key === "ArrowLeft") showLightboxOffset(-1);
    else if (event.key === "ArrowRight") showLightboxOffset(1);
  });

  // ---- Public Gallery tab ----------------------------------------------

  async function loadGallery() {
    showError(els.galleryError, "");
    if (!accountId()) {
      showError(els.galleryError, "Enter an Account ID first.");
      return;
    }
    els.galleryLoad.disabled = true;
    els.galleryStatus.textContent = "Loading...";
    els.galleryGrid.innerHTML = "";
    try {
      const sort = els.gallerySort.value;
      const limit = els.galleryLimit.value;
      const data = await apiFetch(
        `/api/v1/accounts/${encodeURIComponent(accountId())}/gallery?sort=${encodeURIComponent(sort)}&limit=${encodeURIComponent(limit)}`,
      );
      renderGallery(data.items);
      els.galleryStatus.textContent = `${data.count} item(s)`;
    } catch (err) {
      showError(els.galleryError, err.message);
      els.galleryStatus.textContent = "";
    } finally {
      els.galleryLoad.disabled = false;
    }
  }

  let currentGalleryItems = [];

  function renderGallery(items) {
    currentGalleryItems = items;
    if (!items.length) {
      els.galleryGrid.innerHTML = '<p class="status">No items found.</p>';
      return;
    }
    els.galleryGrid.innerHTML = items.map(galleryCardHtml).join("");
    els.galleryGrid.querySelectorAll("[data-track]").forEach((btn) => {
      btn.addEventListener("click", () => trackEvent(btn.dataset.id, btn.dataset.track, btn));
    });
  }

  // Delegated once on the persistent grid container; contents are replaced wholesale each render.
  els.galleryGrid.addEventListener("click", (event) => {
    if (event.target.closest("a")) return;
    if (event.target.closest("button")) return;
    const card = event.target.closest(".card[data-gallery-id]");
    if (!card) return;
    const item = currentGalleryItems.find((i) => i.id === card.dataset.galleryId);
    if (!item || !item.mediaItems.length) return;
    openLightbox(item.mediaItems, item.caption);
  });

  function galleryCardHtml(item) {
    const thumb = item.mediaItems[0];
    const img = thumb
      ? `<img src="${escapeHtml(thumb.smallUrl)}" alt="" loading="lazy" />`
      : '<div class="thumb-placeholder"></div>';
    const clickable = item.mediaItems.length ? "selectable" : "";
    return `
      <div class="card ${clickable}" data-gallery-id="${escapeHtml(item.id)}">
        ${img}
        <div class="card-body">
          <span class="badge">${escapeHtml(item.mediaType)}</span>
          <div class="card-caption">${escapeHtml(item.caption ?? "")}</div>
          <div class="card-meta">
            <span>${escapeHtml(formatDate(item.timestamp))}</span>
            <a href="${escapeHtml(item.permalink)}" target="_blank" rel="noopener">open</a>
          </div>
          <div class="card-meta">
            <span data-views>views: ${item.viewCount}</span>
            <span data-clicks>clicks: ${item.clickCount}</span>
          </div>
          <div class="card-actions">
            <button type="button" data-track="view" data-id="${escapeHtml(item.id)}">Track view</button>
            <button type="button" data-track="click" data-id="${escapeHtml(item.id)}">Track click</button>
          </div>
        </div>
      </div>
    `;
  }

  async function trackEvent(id, event, btn) {
    showError(els.galleryError, "");
    const token = els.apiBearerToken.value.trim();
    if (!token) {
      showError(els.galleryError, "Enter the public bearer token to track events.");
      return;
    }
    btn.disabled = true;
    try {
      await apiFetch(`/api/v1/accounts/${encodeURIComponent(accountId())}/gallery/track`, {
        method: "POST",
        token,
        body: { id, event },
      });
      const counterEl = btn
        .closest(".card")
        .querySelector(event === "view" ? "[data-views]" : "[data-clicks]");
      const label = event === "view" ? "views" : "clicks";
      const current = parseInt(counterEl.textContent.replace(/\D/g, ""), 10) || 0;
      counterEl.textContent = `${label}: ${current + 1}`;
    } catch (err) {
      showError(els.galleryError, err.message);
    } finally {
      btn.disabled = false;
    }
  }

  els.galleryLoad.addEventListener("click", loadGallery);

  // ---- Admin Catalog tab -------------------------------------------------

  let catalogItems = [];
  let baseline = new Map(); // shortcode -> selected boolean, as of last GET/commit
  let pending = new Map(); // shortcode -> current checkbox state
  let currentPage = 0;

  function adminToken() {
    return els.adminToken.value.trim();
  }

  async function loadCatalog() {
    showError(els.catalogError, "");
    if (!accountId()) {
      showError(els.catalogError, "Enter an Account ID first.");
      return;
    }
    if (!adminToken()) {
      showError(els.catalogError, "Enter the admin token first.");
      return;
    }
    els.catalogLoad.disabled = true;
    els.catalogStatus.textContent = "Loading...";
    els.catalogGrid.innerHTML = "";
    try {
      const data = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}/catalog`,
        { token: adminToken() },
      );
      catalogItems = data.items;
      baseline = new Map(catalogItems.map((item) => [item.shortcode, item.selected]));
      pending = new Map(baseline);
      currentPage = 0;
      els.catalogStatus.textContent = `${data.count} item(s)`;
      renderCatalogPage();
    } catch (err) {
      showError(els.catalogError, err.message);
      els.catalogStatus.textContent = "";
    } finally {
      els.catalogLoad.disabled = false;
    }
  }

  const MEDIA_TYPE_FILTERS = [
    ["filterImage", "IMAGE"],
    ["filterCarousel", "CAROUSEL_ALBUM"],
    ["filterVideo", "VIDEO"],
  ];

  function visibleCatalogItems() {
    const activeTypes = MEDIA_TYPE_FILTERS.filter(([field]) => els[field].checked).map(
      ([, type]) => type,
    );
    return catalogItems.filter((item) => {
      if (!els.catalogShowNotDigestible.checked && item.notDigestibleReason) return false;
      if (els.filterSelected.checked && !item.selected) return false;
      if (els.filterPending.checked && pending.get(item.shortcode) === baseline.get(item.shortcode)) {
        return false;
      }
      if (activeTypes.length && !activeTypes.includes(item.mediaType)) return false;
      return true;
    });
  }

  function totalPages() {
    return Math.max(1, Math.ceil(visibleCatalogItems().length / PAGE_SIZE));
  }

  function renderCatalogPage() {
    const items = visibleCatalogItems();
    if (!items.length) {
      els.catalogGrid.innerHTML = '<p class="status">No items found.</p>';
      els.catalogPagination.classList.add("hidden");
      updatePendingSummary();
      return;
    }
    const start = currentPage * PAGE_SIZE;
    const pageItems = items.slice(start, start + PAGE_SIZE);
    els.catalogGrid.innerHTML = pageItems.map(catalogCardHtml).join("");
    els.catalogGrid.querySelectorAll("[data-shortcode]").forEach((checkbox) => {
      checkbox.addEventListener("change", () => {
        pending.set(checkbox.dataset.shortcode, checkbox.checked);
        if (els.filterPending.checked) {
          // Toggling can move this item in/out of the "pending only" view itself.
          renderCatalogPage();
          return;
        }
        checkbox.closest(".card")?.classList.toggle("selected", checkbox.checked);
        updatePendingSummary();
      });
    });

    els.catalogPagination.classList.toggle("hidden", totalPages() <= 1);
    els.catalogPageLabel.textContent = `Page ${currentPage + 1} of ${totalPages()}`;
    els.catalogPrev.disabled = currentPage === 0;
    els.catalogNext.disabled = currentPage >= totalPages() - 1;
    updatePendingSummary();
  }

  // Delegated once on the persistent grid container (its contents are replaced wholesale on
  // every render, so per-card listeners would need re-attaching each time otherwise).
  els.catalogGrid.addEventListener("click", (event) => {
    if (event.target.closest("a")) return;
    if (event.target.matches('input[type="checkbox"]')) return;
    const card = event.target.closest(".card[data-card-shortcode]");
    if (!card) return;
    const checkbox = card.querySelector("input[data-shortcode]");
    if (!checkbox || checkbox.disabled) return;
    checkbox.checked = !checkbox.checked;
    checkbox.dispatchEvent(new Event("change"));
  });

  function catalogCardHtml(item) {
    const thumb = item.thumbnailUrl
      ? `<img src="${escapeHtml(item.thumbnailUrl)}" alt="" loading="lazy" />`
      : '<div class="thumb-placeholder"></div>';
    const disabled = item.notDigestibleReason ? "disabled" : "";
    const isChecked = Boolean(pending.get(item.shortcode));
    const checked = isChecked ? "checked" : "";
    const warning = item.notDigestibleReason
      ? `<span class="badge badge-warning">not digestible: ${escapeHtml(item.notDigestibleReason)}</span>`
      : "";
    const cardClasses = ["card"];
    if (!item.notDigestibleReason) cardClasses.push("selectable");
    if (isChecked) cardClasses.push("selected");
    return `
      <div class="${cardClasses.join(" ")}" data-card-shortcode="${escapeHtml(item.shortcode)}">
        ${thumb}
        <div class="card-body">
          <span class="badge">${escapeHtml(item.mediaType)}</span>
          ${warning}
          <div class="card-caption">${escapeHtml(item.caption ?? "")}</div>
          <div class="card-meta">
            <span>${escapeHtml(item.shortcode)}</span>
            <a href="${escapeHtml(item.permalink)}" target="_blank" rel="noopener">open</a>
          </div>
          <div class="card-meta">
            <span>${escapeHtml(formatDate(item.timestamp))}</span>
          </div>
          <label class="select-row">
            <input type="checkbox" data-shortcode="${escapeHtml(item.shortcode)}" ${checked} ${disabled} />
            Selected
          </label>
        </div>
      </div>
    `;
  }

  function diffSelection() {
    const select = [];
    const deselect = [];
    for (const [shortcode, isSelected] of pending) {
      if (isSelected === baseline.get(shortcode)) continue;
      (isSelected ? select : deselect).push(shortcode);
    }
    return { select, deselect };
  }

  function updatePendingSummary() {
    const { select, deselect } = diffSelection();
    const total = select.length + deselect.length;
    els.pendingSummary.textContent = total
      ? `${total} pending: ${select.length} to select, ${deselect.length} to deselect`
      : "";
    els.catalogCommit.disabled = total === 0;
    els.catalogReset.disabled = total === 0;
  }

  [
    els.filterSelected,
    els.filterPending,
    els.filterImage,
    els.filterCarousel,
    els.filterVideo,
    els.catalogShowNotDigestible,
  ].forEach((checkbox) => {
    checkbox.addEventListener("change", () => {
      currentPage = 0;
      renderCatalogPage();
    });
  });

  els.catalogReset.addEventListener("click", () => {
    pending = new Map(baseline);
    renderCatalogPage();
  });

  els.catalogPrev.addEventListener("click", () => {
    currentPage = Math.max(0, currentPage - 1);
    renderCatalogPage();
  });

  els.catalogNext.addEventListener("click", () => {
    currentPage = Math.min(totalPages() - 1, currentPage + 1);
    renderCatalogPage();
  });

  els.catalogCommit.addEventListener("click", async () => {
    showError(els.catalogError, "");
    const { select, deselect } = diffSelection();
    if (!select.length && !deselect.length) return;
    els.catalogCommit.disabled = true;
    els.catalogStatus.textContent = "Committing selection...";
    try {
      const result = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}/catalog/selections`,
        { method: "PATCH", token: adminToken(), body: { select, deselect } },
      );
      els.catalogStatus.textContent =
        `Selected: ${result.selected.length}, Deselected: ${result.deselected.length}, ` +
        `Not found: ${result.notFound.length}`;
      // Refresh from server truth so baseline/thumbnails/pagination stay correct.
      await loadCatalog();
    } catch (err) {
      showError(els.catalogError, err.message);
      updatePendingSummary();
    }
  });

  els.catalogLoad.addEventListener("click", loadCatalog);
})();
