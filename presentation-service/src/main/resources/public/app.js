(() => {
  "use strict";

  const els = {
    accountId: document.getElementById("accountId"),
    apiBearerToken: document.getElementById("apiBearerToken"),
    adminToken: document.getElementById("adminToken"),
    tabButtons: document.querySelectorAll(".tab-button"),
    tabPanels: document.querySelectorAll(".tab-panel"),

    gallerySelect: document.getElementById("gallerySelect"),
    galleryRefresh: document.getElementById("galleryRefresh"),
    gallerySort: document.getElementById("gallerySort"),
    galleryLimit: document.getElementById("galleryLimit"),
    galleryLoad: document.getElementById("galleryLoad"),
    galleryError: document.getElementById("galleryError"),
    galleryStatus: document.getElementById("galleryStatus"),
    galleryGrid: document.getElementById("galleryGrid"),
    galleryPagination: document.getElementById("galleryPagination"),
    galleryPrev: document.getElementById("galleryPrev"),
    galleryNext: document.getElementById("galleryNext"),
    galleryPageLabel: document.getElementById("galleryPageLabel"),

    adminGallerySelect: document.getElementById("adminGallerySelect"),
    galleryNewName: document.getElementById("galleryNewName"),
    galleryCreate: document.getElementById("galleryCreate"),
    galleryRename: document.getElementById("galleryRename"),
    galleryDelete: document.getElementById("galleryDelete"),
    galleryManagerStatus: document.getElementById("galleryManagerStatus"),
    galleryManagerError: document.getElementById("galleryManagerError"),
    filterInGallery: document.getElementById("filterInGallery"),
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

  // Both list endpoints return { data, pagination: { totalRecords, currentPage, totalPages, links } }.
  // Navigation follows pagination.links verbatim rather than rebuilding a URL here: the links are
  // relative and already carry the sort/filter parameters, so the client never has to keep its own
  // idea of the query in sync with the server's.
  function renderPagination(els_, pagination) {
    const { currentPage, totalPages, links } = pagination;
    els_.pagination.classList.toggle("hidden", totalPages <= 1);
    els_.pageLabel.textContent = `Page ${currentPage} of ${totalPages}`;
    els_.prev.disabled = !links.prev;
    els_.next.disabled = !links.next;
    return links;
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

  // Links from the most recent gallery response, so Prev/Next can follow them directly.
  let galleryLinks = { prev: null, next: null };

  function selectedGalleryId() {
    return els.gallerySelect.value || "";
  }

  function galleryFirstPageUrl() {
    const sort = els.gallerySort.value;
    const limit = els.galleryLimit.value;
    return (
      `/api/v1/accounts/${encodeURIComponent(accountId())}/galleries/${encodeURIComponent(selectedGalleryId())}` +
      `?sort=${encodeURIComponent(sort)}&limit=${encodeURIComponent(limit)}&page=1`
    );
  }

  // `url` is a pagination link when paging, and undefined when loading fresh from the controls.
  async function loadGallery(url) {
    showError(els.galleryError, "");
    if (!accountId()) {
      showError(els.galleryError, "Enter an Account ID first.");
      return;
    }
    if (!selectedGalleryId()) {
      showError(els.galleryError, "Pick a gallery first. If the list is empty, create one on the Admin tab.");
      return;
    }
    els.galleryLoad.disabled = true;
    els.galleryStatus.textContent = "Loading...";
    els.galleryGrid.innerHTML = "";
    try {
      const body = await apiFetch(url ?? galleryFirstPageUrl());
      renderGallery(body.data);
      galleryLinks = renderPagination(
        {
          pagination: els.galleryPagination,
          pageLabel: els.galleryPageLabel,
          prev: els.galleryPrev,
          next: els.galleryNext,
        },
        body.pagination,
      );
      els.galleryStatus.textContent = `${body.pagination.totalRecords} item(s)`;
    } catch (err) {
      showError(els.galleryError, err.message);
      els.galleryStatus.textContent = "";
      els.galleryPagination.classList.add("hidden");
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
      const trackUrl =
        `/api/v1/accounts/${encodeURIComponent(accountId())}` +
        `/galleries/${encodeURIComponent(selectedGalleryId())}/track`;
      await apiFetch(trackUrl, {
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

  els.galleryLoad.addEventListener("click", () => loadGallery());
  els.galleryPrev.addEventListener("click", () => galleryLinks.prev && loadGallery(galleryLinks.prev));
  els.galleryNext.addEventListener("click", () => galleryLinks.next && loadGallery(galleryLinks.next));

  // ---- Admin Catalog tab -------------------------------------------------

  let catalogItems = []; // the current page only; the rest of the catalog lives on the server
  // baseline/pending are keyed by shortcode and accumulate across every page visited since the
  // last commit, so a toggle made on page 1 survives a trip to page 2 and one Commit still sends
  // every unsaved change in a single PATCH.
  let baseline = new Map(); // shortcode -> selected, as the server last reported it
  let pending = new Map(); // shortcode -> current checkbox state
  let catalogLinks = { prev: null, next: null };
  let catalogSelfLink = null; // the page to return to after committing

  const MEDIA_TYPE_FILTERS = [
    ["filterImage", "IMAGE"],
    ["filterCarousel", "CAROUSEL_ALBUM"],
    ["filterVideo", "VIDEO"],
  ];

  function adminToken() {
    return els.adminToken.value.trim();
  }

  // Everything except the Pending filter is applied server-side, so a filter narrows the whole
  // catalog rather than just the rows that happen to be loaded.
  function adminGalleryId() {
    return els.adminGallerySelect.value || "";
  }

  /** Membership is per gallery now, so "checked" means "in the gallery currently being curated". */
  function isInCuratedGallery(item) {
    const id = adminGalleryId();
    return Boolean(id) && item.galleryIds.includes(id);
  }

  function catalogFirstPageUrl() {
    const params = new URLSearchParams();
    // The checkbox means "in the gallery I am curating", so it filters by that gallery's id
    // server-side rather than by any global notion of membership.
    if (els.filterInGallery.checked && adminGalleryId()) params.set("galleryId", adminGalleryId());
    for (const [field, type] of MEDIA_TYPE_FILTERS) {
      if (els[field].checked) params.append("mediaType", type);
    }
    if (!els.catalogShowNotDigestible.checked) params.set("includeNotDigestible", "false");
    params.set("page", "1");
    return `/api/v1/admin/accounts/${encodeURIComponent(accountId())}/catalog?${params}`;
  }

  // `url` is a pagination link when paging, and undefined to (re)load page 1 from the filters.
  async function loadCatalog(url) {
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
      const body = await apiFetch(url ?? catalogFirstPageUrl(), { token: adminToken() });
      catalogItems = body.data;
      for (const item of catalogItems) {
        baseline.set(item.shortcode, isInCuratedGallery(item));
        // Merge rather than overwrite: an unsaved toggle for this shortcode outranks what the
        // server just said, or paging away and back would silently discard it.
        if (!pending.has(item.shortcode)) pending.set(item.shortcode, isInCuratedGallery(item));
      }
      catalogSelfLink = body.pagination.links.self;
      catalogLinks = renderPagination(
        {
          pagination: els.catalogPagination,
          pageLabel: els.catalogPageLabel,
          prev: els.catalogPrev,
          next: els.catalogNext,
        },
        body.pagination,
      );
      els.catalogStatus.textContent = `${body.pagination.totalRecords} item(s)`;
      renderCatalogPage();
    } catch (err) {
      showError(els.catalogError, err.message);
      els.catalogStatus.textContent = "";
      els.catalogPagination.classList.add("hidden");
    } finally {
      els.catalogLoad.disabled = false;
    }
  }

  // The only filter left on the client: "pending" means unsaved browser state, which the server
  // has no way to know about. It therefore narrows the current page rather than the whole catalog.
  function visibleCatalogItems() {
    if (!els.filterPending.checked) return catalogItems;
    return catalogItems.filter((item) => pending.get(item.shortcode) !== baseline.get(item.shortcode));
  }

  function renderCatalogPage() {
    const items = visibleCatalogItems();
    if (!items.length) {
      els.catalogGrid.innerHTML = '<p class="status">No items found.</p>';
      updatePendingSummary();
      return;
    }
    els.catalogGrid.innerHTML = items.map(catalogCardHtml).join("");
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

    updatePendingSummary();
  }

  // Delegated once on the persistent grid container (its contents are replaced wholesale on
  // every render, so per-card listeners would need re-attaching each time otherwise).
  els.catalogGrid.addEventListener("click", (event) => {
    if (event.target.closest("a")) return;
    if (event.target.matches('input[type="checkbox"]')) return;
    // The purge button lives inside the card and has its own handler on this same element, so
    // stopPropagation there would not stop this one - it has to opt out explicitly.
    if (event.target.closest("button[data-purge-shortcode]")) return;
    const card = event.target.closest(".card[data-card-shortcode]");
    if (!card) return;
    const checkbox = card.querySelector("input[data-shortcode]");
    if (!checkbox || checkbox.disabled) return;
    checkbox.checked = !checkbox.checked;
    checkbox.dispatchEvent(new Event("change"));
  });

  // Mirrors the server's retention default. Only ever used to render an approximate countdown; the
  // server alone decides when media actually goes, so being out of date here is cosmetic.
  const DEFAULT_RETENTION_DAYS = 30;

  // Shows that a deselected item's media is on a deletion clock, and that the decision is still
  // reversible until it runs out - reselecting inside the window restores it with no re-download.
  function lifecycleBadge(item) {
    if (item.purgeRequestedAt) {
      return '<span class="badge badge-warning">purge pending</span>';
    }
    if (!item.deselectedAt) return "";
    const deletesAt = new Date(item.deselectedAt).getTime() + DEFAULT_RETENTION_DAYS * 86400000;
    const daysLeft = Math.ceil((deletesAt - Date.now()) / 86400000);
    const label = daysLeft > 0 ? `deletes in ${daysLeft}d` : "deletes next cycle";
    return `<span class="badge badge-warning">${escapeHtml(label)}</span>`;
  }

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
    const lifecycle = lifecycleBadge(item);
    // Only an item with downloaded media has anything to purge, and only the server knows for sure.
    // A deselected item is still worth offering it for, since its media survives the grace period.
    const purgeAction = item.galleryIds.length > 0 || item.deselectedAt
      ? `<button type="button" class="link-button danger" data-purge-shortcode="${escapeHtml(item.shortcode)}">Delete now</button>`
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
          ${lifecycle}
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
          ${purgeAction}
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

  // These four are server-side filters, so changing one has to refetch from page 1 - the rows that
  // match may live anywhere in the catalog, not just in the page currently loaded.
  [
    els.filterInGallery,
    els.filterImage,
    els.filterCarousel,
    els.filterVideo,
    els.catalogShowNotDigestible,
  ].forEach((checkbox) => {
    checkbox.addEventListener("change", () => loadCatalog());
  });

  // Pending is client-side state, so it only re-renders what is already loaded.
  els.filterPending.addEventListener("change", renderCatalogPage);

  els.catalogReset.addEventListener("click", () => {
    pending = new Map(baseline);
    renderCatalogPage();
  });

  els.catalogPrev.addEventListener("click", () => catalogLinks.prev && loadCatalog(catalogLinks.prev));
  els.catalogNext.addEventListener("click", () => catalogLinks.next && loadCatalog(catalogLinks.next));

  els.catalogCommit.addEventListener("click", async () => {
    showError(els.catalogError, "");
    const { select, deselect } = diffSelection();
    if (!select.length && !deselect.length) return;
    if (!adminGalleryId()) {
      showError(els.catalogError, "Pick a gallery to curate first.");
      return;
    }
    els.catalogCommit.disabled = true;
    els.catalogStatus.textContent = "Committing membership...";
    try {
      const result = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}` +
          `/galleries/${encodeURIComponent(adminGalleryId())}/items`,
        { method: "PATCH", token: adminToken(), body: { add: select, remove: deselect } },
      );
      els.catalogStatus.textContent =
        `Added: ${result.added.length}, Removed: ${result.removed.length}, ` +
        `Already present: ${result.alreadyPresent.length}, Not found: ${result.notFound.length}`;
      // Every pending change has now been applied, so drop the accumulated state entirely and
      // refresh from server truth - staying on the page being viewed rather than jumping to 1.
      baseline = new Map();
      pending = new Map();
      await loadCatalog(catalogSelfLink ?? undefined);
    } catch (err) {
      showError(els.catalogError, err.message);
      updatePendingSummary();
    }
  });

  // Delegated, because cards are re-rendered wholesale on every catalog load.
  els.catalogGrid.addEventListener("click", async (event) => {
    const button = event.target.closest("button[data-purge-shortcode]");
    if (!button) return;

    const shortcode = button.dataset.purgeShortcode;
    const confirmed = window.confirm(
      `Delete the downloaded media for ${shortcode}?\n\n` +
        "It leaves the gallery immediately and its files are deleted on the next ingestion cycle. " +
        "The catalog entry stays, so you can select it again later to download it afresh.",
    );
    if (!confirmed) return;

    showError(els.catalogError, "");
    button.disabled = true;
    els.catalogStatus.textContent = `Deleting ${shortcode}...`;
    try {
      const result = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}/catalog/media`,
        { method: "DELETE", token: adminToken(), body: { shortcodes: [shortcode] } },
      );
      const status = result.results[0]?.status ?? "UNKNOWN";
      els.catalogStatus.textContent =
        status === "ACCEPTED"
          ? `${shortcode}: media will be deleted on the next ingestion cycle`
          : `${shortcode}: ${status}`;
      // The purge deselects server-side, so any pending state for this card is now stale.
      baseline = new Map();
      pending = new Map();
      await loadCatalog(catalogSelfLink ?? undefined);
    } catch (err) {
      button.disabled = false;
      showError(els.catalogError, err.message);
    }
  });

  els.catalogLoad.addEventListener("click", () => loadCatalog());

  // ---------------------------------------------------------------------------
  // Galleries
  //
  // An account has any number of named galleries and no default one, so both tabs
  // need to know which gallery they are pointed at before they can do anything.
  // The list is public, so the gallery tab loads it without a token; the admin tab
  // reuses the same payload rather than fetching it twice.
  // ---------------------------------------------------------------------------

  let galleries = [];

  function renderGallerySelect(select, previousValue) {
    if (!galleries.length) {
      select.innerHTML = '<option value="">(no galleries yet)</option>';
      select.value = "";
      return;
    }
    select.innerHTML = galleries
      .map(
        (g) =>
          `<option value="${escapeHtml(g.id)}">${escapeHtml(g.name)} (${g.publishedCount}/${g.itemCount})</option>`,
      )
      .join("");
    // Keep the current pick across a refresh where possible, so renaming or creating
    // a gallery doesn't silently move the admin to a different one mid-curation.
    const stillThere = galleries.some((g) => g.id === previousValue);
    select.value = stillThere ? previousValue : galleries[0].id;
  }

  function currentGallery() {
    return galleries.find((g) => g.id === adminGalleryId()) ?? null;
  }

  function updateGalleryManagerButtons() {
    const hasSelection = Boolean(adminGalleryId());
    els.galleryRename.disabled = !hasSelection;
    els.galleryDelete.disabled = !hasSelection;
  }

  async function loadGalleries() {
    if (!accountId()) return;
    const previousPublic = els.gallerySelect.value;
    const previousAdmin = els.adminGallerySelect.value;
    try {
      const body = await apiFetch(
        `/api/v1/accounts/${encodeURIComponent(accountId())}/galleries?limit=50&page=1`,
      );
      galleries = body.data;
      renderGallerySelect(els.gallerySelect, previousPublic);
      renderGallerySelect(els.adminGallerySelect, previousAdmin);
      updateGalleryManagerButtons();
    } catch (err) {
      showError(els.galleryManagerError, err.message);
    }
  }

  /** Membership is per gallery, so switching galleries invalidates every unsaved toggle. */
  function resetCurationState() {
    baseline = new Map();
    pending = new Map();
    els.catalogGrid.innerHTML = "";
    els.catalogStatus.textContent = "";
    els.catalogPagination.classList.add("hidden");
    updatePendingSummary();
  }

  els.galleryRefresh.addEventListener("click", loadGalleries);
  els.gallerySelect.addEventListener("change", () => loadGallery());

  els.adminGallerySelect.addEventListener("change", () => {
    updateGalleryManagerButtons();
    resetCurationState();
  });

  els.galleryCreate.addEventListener("click", async () => {
    showError(els.galleryManagerError, "");
    const name = els.galleryNewName.value.trim();
    if (!name) {
      showError(els.galleryManagerError, "Enter a name for the new gallery.");
      return;
    }
    if (!adminToken()) {
      showError(els.galleryManagerError, "Enter an Admin Token first.");
      return;
    }
    els.galleryCreate.disabled = true;
    try {
      const created = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}/galleries`,
        { method: "POST", token: adminToken(), body: { name } },
      );
      els.galleryNewName.value = "";
      els.galleryManagerStatus.textContent = `Created "${created.name}"`;
      await loadGalleries();
      els.adminGallerySelect.value = created.id;
      updateGalleryManagerButtons();
      resetCurationState();
    } catch (err) {
      showError(els.galleryManagerError, err.message);
    } finally {
      els.galleryCreate.disabled = false;
    }
  });

  els.galleryRename.addEventListener("click", async () => {
    showError(els.galleryManagerError, "");
    const gallery = currentGallery();
    if (!gallery) return;
    const name = window.prompt(`Rename "${gallery.name}" to:`, gallery.name);
    if (name === null) return;
    if (!name.trim()) {
      showError(els.galleryManagerError, "A gallery name cannot be empty.");
      return;
    }
    try {
      const renamed = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}/galleries/${encodeURIComponent(gallery.id)}`,
        { method: "PATCH", token: adminToken(), body: { name: name.trim() } },
      );
      // The id is unchanged by design, so the selection and any links survive a rename.
      els.galleryManagerStatus.textContent = `Renamed to "${renamed.name}"`;
      await loadGalleries();
    } catch (err) {
      showError(els.galleryManagerError, err.message);
    }
  });

  els.galleryDelete.addEventListener("click", async () => {
    showError(els.galleryManagerError, "");
    const gallery = currentGallery();
    if (!gallery) return;
    const confirmed = window.confirm(
      `Delete the gallery "${gallery.name}"?\n\n` +
        `It holds ${gallery.itemCount} item(s). Any item that is in no other gallery starts its ` +
        `retention countdown, after which its downloaded media is deleted. The catalog entries stay, ` +
        `so nothing is lost from the browse list.`,
    );
    if (!confirmed) return;
    try {
      const result = await apiFetch(
        `/api/v1/admin/accounts/${encodeURIComponent(accountId())}` +
          `/galleries/${encodeURIComponent(gallery.id)}?force=true`,
        { method: "DELETE", token: adminToken() },
      );
      els.galleryManagerStatus.textContent =
        `Deleted "${result.name}" (${result.itemsRemoved} item(s) removed, ` +
        `${result.itemsReleased} now on a retention clock)`;
      await loadGalleries();
      resetCurationState();
    } catch (err) {
      showError(els.galleryManagerError, err.message);
    }
  });

  els.accountId.addEventListener("change", loadGalleries);
  if (accountId()) loadGalleries();

})();
