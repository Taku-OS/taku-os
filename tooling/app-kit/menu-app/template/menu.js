(() => {
  'use strict';

  const number = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 });
  const shortDate = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' });
  const fullDate = new Intl.DateTimeFormat('en-US', {
    month: 'long', day: 'numeric', year: 'numeric'
  });
  const time = new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' });
  const nutrients = ['kcal', 'protein', 'carbs', 'fat'];
  const icons = {
    back: '<path d="m14.5 5.5-6.5 6.5 6.5 6.5"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    bag: '<path d="M5 8h14l1.5 13h-17L5 8Z"/><path d="M9 9V6a3 3 0 0 1 6 0v3"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    play: '<path d="M9 5.5 19 12 9 18.5Z" fill="currentColor" stroke="none"/>',
    external: '<path d="M13 5h6v6m0-6-9 9m0-7H5v12h12v-5"/>',
    health: '<path d="M3 12h5l2-5 3 10 2-5h6"/><path d="M20 8a8 8 0 1 0 0 8"/>',
    sparkle: '<path d="M12 1.5 14.7 9.3 22.5 12l-7.8 2.7L12 22.5l-2.7-7.8L1.5 12l7.8-2.7Z" fill="currentColor" stroke="none"/>',
    more: '<path d="m6 9 6 6 6-6"/>',
    flame: '<path d="M13 3c.5 4-3.5 5-3.5 8.5a3.5 3.5 0 0 0 2.5 3.3c-.5-2 1-3.3 2-4.3 2 2.1 3.5 3.6 3.5 6a5.5 5.5 0 0 1-11 0C6.5 11.5 9 8 13 3Z"/>',
    protein: '<path d="m8 8 8 8M6.6 5.3l-2 2a1.4 1.4 0 0 0 0 2l1.1 1.1 4.2-4.2-1.3-1.1a1.4 1.4 0 0 0-2 .2ZM17.4 18.7l2-2a1.4 1.4 0 0 0 0-2l-1.1-1.1-4.2 4.2 1.3 1.1a1.4 1.4 0 0 0 2-.2Z"/>',
    carbs: '<path d="M12 21V9m0 3C8.6 12 6 10 6 7c3.2 0 6 2 6 5Zm0 4c-3.4 0-6-2-6-5 3.2 0 6 2 6 5Zm0-4c3.4 0 6-2 6-5-3.2 0-6 2-6 5Zm0 4c3.4 0 6-2 6-5-3.2 0-6 2-6 5Zm0-7c-2-1.7-2-3.8 0-5.5 2 1.7 2 3.8 0 5.5Z"/>',
    fat: '<path d="M12 3.5c2.6 4 5.5 7.1 5.5 10.6a5.5 5.5 0 0 1-11 0c0-3.5 2.9-6.6 5.5-10.6Zm-3 11a3 3 0 0 0 3 3"/>',
    meal: '<path d="M4 12h16a8 8 0 0 1-16 0Zm4 8h8m-8-11c-1.1-1.2 1-2.2 0-3.5m4 3.5c-1.1-1.2 1-2.2 0-3.5m4 3.5c-1.1-1.2 1-2.2 0-3.5"/>'
  };

  function icon(name) {
    return `<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">${icons[name]}</svg>`;
  }

  function dayKey(date) {
    return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
  }

  function portionValue(item, nutrient) {
    const value = item[nutrient];
    return Number.isFinite(value) && value >= 0 ? value * item.quantity : null;
  }

  function totals(items) {
    return Object.fromEntries(nutrients.map(nutrient => {
      const amounts = items.map(item => portionValue(item, nutrient));
      return [nutrient, amounts.includes(null) ? null : amounts.reduce((sum, value) => sum + value, 0)];
    }));
  }

  function valueText(value) {
    return value === null ? '—' : number.format(value);
  }

  function amountText(value, unit) {
    return value === null ? 'Not available' : `${number.format(value)} ${unit}`;
  }

  function calorieSummary(date, kcal) {
    return `<section class="m08-intake-card m08-intake-summary" aria-label="Today’s calorie intake">
      <p class="m08-intake-date">${shortDate.format(date)}</p>
      <div class="m08-intake-summary-body">
        <div class="m08-intake-amount">
          <p>Today’s intake</p>
          ${kcal === null ? '<strong aria-label="Not available">—</strong>' : `<strong>${valueText(kcal)}<small>kcal</small></strong>`}
        </div>
        <div class="m08-intake-energy-icon" aria-hidden="true">${icon('flame')}</div>
      </div>
    </section>`;
  }

  function metrics(values) {
    const entries = [
      ['Protein', 'protein', values.protein],
      ['Carbs', 'carbs', values.carbs],
      ['Fat', 'fat', values.fat]
    ];
    return `<section class="m08-intake-metrics" aria-label="Nutrient totals">
      ${entries.map(([label, glyph, value]) => `<div class="m08-intake-card m08-intake-metric">
        <span class="m08-intake-metric-icon">${icon(glyph)}</span>
        <div><p>${label}</p>
          ${value === null ? '<strong aria-label="Not available">—</strong>' : `<strong>${valueText(value)}<small>g</small></strong>`}
        </div>
      </div>`).join('')}
    </section>`;
  }

  function itemList(items) {
    return `<ul class="m08-intake-dishes">${items.map(item => `<li>
      <span class="m08-intake-thumb">${localImage(item.image)
        ? `<img src="${escape(localImage(item.image))}" alt="" draggable="false">`
        : icon('meal')}</span>
      <div>
        <p>${escape(item.name)}</p>
        <span>${amountText(portionValue(item, 'kcal'), 'kcal')} <span aria-hidden="true">·</span>
          ${number.format(item.quantity)} ${item.quantity === 1 ? 'serving' : 'servings'}</span>
      </div>
    </li>`).join('')}</ul>`;
  }

  function empty(message) {
    return `<div class="m08-intake-empty">${icon('meal')}<p>${message}</p></div>`;
  }

  function todayItems(items) {
    const previewCount = 4;
    if (items.length <= previewCount) return itemList(items);
    const extra = items.slice(previewCount);
    return `${itemList(items.slice(0, previewCount))}
      <details class="m08-intake-more">
        <summary>
          <span class="m08-intake-more-closed">Show ${number.format(extra.length)} more</span>
          <span class="m08-intake-more-open">Show less</span>
          ${icon('more')}
        </summary>
        ${itemList(extra)}
      </details>`;
  }

  function today(meals, date) {
    const items = meals.filter(meal => dayKey(meal.date) === dayKey(date)).flatMap(meal => meal.items);
    const values = totals(items);
    return `${calorieSummary(date, values.kcal)}${metrics(values)}
      <section class="m08-intake-card m08-intake-meals" aria-label="Today’s meals">
        <header><h2>Meals</h2><span>${items.length} ${items.length === 1 ? 'item' : 'items'}</span></header>
        ${items.length ? todayItems(items) : empty('Finish an order to add your meal.')}
      </section>
      ${items.length ? '<p class="m08-intake-footnote">Nutrition estimates from recorded orders.</p>' : ''}`;
  }

  function history(meals, date) {
    if (!meals.length) {
      return `<section class="m08-intake-card m08-intake-meals">
        <header><h2>Meal history</h2></header>${empty('Your recorded meals will appear here.')}
      </section>`;
    }
    const days = new Map();
    for (const meal of meals) {
      const key = dayKey(meal.date);
      if (!days.has(key)) days.set(key, []);
      days.get(key).push(meal);
    }
    return [...days.values()].map(entries => {
      const first = entries[0].date;
      const dayTotal = totals(entries.flatMap(meal => meal.items));
      return `<section class="m08-intake-card m08-intake-meals m08-intake-history-day">
        <header><h2>${dayKey(first) === dayKey(date) ? 'Today' : fullDate.format(first)}</h2>
          <span>${amountText(dayTotal.kcal, 'kcal')}</span></header>
        ${entries.map(meal => `<article class="m08-intake-history-meal">
          <p>${time.format(meal.date)}</p>${itemList(meal.items)}
        </article>`).join('')}
      </section>`;
    }).join('') + '<p class="m08-intake-footnote">Nutrition estimates from recorded orders.</p>';
  }

  function renderHealth(meals, view) {
    const date = new Date();
    const validMeals = meals.filter(meal => Array.isArray(meal?.items)).map(meal => ({
      date: new Date(meal.recordedAt),
      items: meal.items.filter(item => Number.isSafeInteger(item?.quantity) && item.quantity > 0)
    })).filter(meal => Number.isFinite(meal.date.getTime()) && meal.items.length)
      .sort((first, second) => second.date - first.date);
    const isHistory = view === 'history';
    return `<div class="m08-intake">
      <header class="m08-intake-header">
        <h1>My Health</h1>
        <button type="button" data-m08-action="intake-back">${icon('back')} Menu</button>
      </header>
      <nav class="m08-intake-tabs" aria-label="Intake views">
        <button type="button" data-m08-action="intake-today" aria-pressed="${!isHistory}">Today</button>
        <button type="button" data-m08-action="intake-history" aria-pressed="${isHistory}">History</button>
      </nav>
      <div class="m08-intake-scroll">
        ${isHistory ? history(validMeals, date) : today(validMeals, date)}
      </div>
    </div>`;
  }

  function escape(value) {
    return String(value ?? '').replace(/[&<>"']/g, char => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[char]);
  }

  function text(value) {
    return typeof value === 'string' ? value.trim() : '';
  }

  function localImage(value) {
    const path = text(value);
    return /^images\/[A-Za-z0-9][A-Za-z0-9_-]{0,79}\.(?:jpe?g|png|webp)$/.test(path) ? path : '';
  }

  function originalUrl(value) {
    const url = text(value);
    try {
      const target = new URL(url);
      return target.protocol === 'https:' && !target.username && !target.password ? url : '';
    } catch {
      return '';
    }
  }

  function readCatalog(bridge) {
    let data;
    try {
      data = bridge ? JSON.parse(bridge.readMenu()) : window.TAKU_MENU_DATA;
    } catch {
      data = null;
    }
    const rawVenue = data?.venue;
    const venue = {
      name: text(rawVenue?.name),
      address: text(rawVenue?.address),
      placeId: rawVenue?.verified === false ? '' : text(rawVenue?.placeId),
    };
    const ids = new Set();
    const dishes = [];
    for (const item of Array.isArray(data?.dishes) ? data.dishes : []) {
      const id = text(item?.id);
      const name = text(item?.name);
      if (!id || !name || ids.has(id)) continue;
      ids.add(id);
      const nutrition = Object.fromEntries(nutrients.map(key => {
        const value = item[key];
        return [key, Number.isFinite(value) && value >= 0 ? value : null];
      }));
      dishes.push({
        ...nutrition,
        id,
        name,
        priceMinor: Number.isSafeInteger(item.priceMinor) && item.priceMinor >= 0 ? item.priceMinor : null,
        ingredients: Array.isArray(item.ingredients) ? item.ingredients.map(text).filter(Boolean) : [],
        image: localImage(item.image),
        recommendations: Array.isArray(item.recommendations) ? item.recommendations : [],
      });
    }
    return { menuId: text(data?.menuId), currency: text(data?.currency).toUpperCase(), venue, dishes };
  }

  function priceFormatter(currency) {
    let format;
    if (/^[A-Z]{3}$/.test(currency)) {
      format = new Intl.NumberFormat('en-US', { style: 'currency', currency });
    }
    const fractionDigits = format?.resolvedOptions().maximumFractionDigits;
    return minor => minor === null || !format ? '—' : format.format(minor / (10 ** fractionDigits));
  }

  function readState(key) {
    try {
      const saved = JSON.parse(window.localStorage.getItem(key));
      return {
        order: new Map((Array.isArray(saved?.order) ? saved.order : []).filter(entry => (
          Array.isArray(entry) && typeof entry[0] === 'string' && Number.isSafeInteger(entry[1]) && entry[1] > 0
        ))),
        meals: Array.isArray(saved?.meals) ? saved.meals : [],
      };
    } catch {
      return { order: new Map(), meals: [] };
    }
  }

  function writeState(key, order, meals) {
    try {
      window.localStorage.setItem(key, JSON.stringify({ order: Array.from(order), meals }));
      return true;
    } catch {
      return false;
    }
  }

  function mount(root, bridge) {
    const { menuId, currency, venue, dishes } = readCatalog(bridge);
    const storageKey = `taku-menu:v1:${menuId || venue.placeId || location.pathname}`;
    const state = readState(storageKey);
    const cart = state.order;
    const price = priceFormatter(currency);
    const venueName = venue.name || 'Scanned menu';
    const calorieRank = dishes.filter(dish => dish.kcal !== null)
      .sort((first, second) => first.kcal - second.kcal);

    const app = document.createElement('section');
    app.setAttribute('aria-label', 'Ordering app');
    document.title = venueName;
    root.replaceChildren(app);

    let dishIndex = 0;
    let view = 'menu';
    let returnView = 'menu';
    let intakeView = 'today';
    let menuScroll = 0;
    let finishError = false;
    let swipeStart;
    let ignoreGalleryClickUntil = 0;

    const quantity = id => cart.get(id) ?? 0;
    const orderLines = () => dishes.filter(dish => quantity(dish.id) > 0);
    const orderCount = () => orderLines().reduce((count, dish) => count + quantity(dish.id), 0);
    const orderTotal = () => {
      const lines = orderLines();
      return lines.some(dish => dish.priceMinor === null)
        ? null
        : lines.reduce((total, dish) => total + quantity(dish.id) * dish.priceMinor, 0);
    };

    function changeQuantity(id, delta) {
      const count = quantity(id) + delta;
      if (count > 0) cart.set(id, count);
      else cart.delete(id);
      writeState(storageKey, cart, state.meals);
    }

    function foodImage(dish, className = '', alt = '', fallback = 'meal') {
      return dish.image
        ? `<img class="${className}" src="${escape(dish.image)}" alt="${escape(alt)}" draggable="false">`
        : `<span class="${className} m08app-media-fallback" aria-hidden="true">${icon(fallback)}</span>`;
    }

    function orderButton() {
      const count = orderCount();
      return `<button type="button" class="m08app-order-pill" data-m08-action="order">
        ${icon('bag')} My order${count ? `<span class="m08app-count">${count}</span>` : ''}
      </button>`;
    }

    function header(title, action, label, trailing = '') {
      return `<header class="m08app-header">
        ${action ? `<button type="button" class="m08app-icon-button" data-m08-action="${action}" aria-label="${label}">${icon('back')}</button>` : ''}
        <span class="m08app-header-title">${title}</span>
        ${trailing}
      </header>`;
    }

    function menu() {
      return `${header('Menu', null, '', orderButton())}
        <div class="m08app-menu-scroll">
          <div class="m08app-menu-heading">
            <h1>${escape(venueName)}</h1>
            <div class="m08app-menu-meta">
              ${venue.address ? `<p>${escape(venue.address)}</p>` : ''}
              <button type="button" class="m08app-health-pill" data-m08-action="intake">${icon('health')} My Health</button>
            </div>
          </div>
          ${dishes.length ? `<div class="m08app-grid">
            ${dishes.map((dish, index) => `
              <button type="button" class="m08app-dish-tile" data-m08-action="dish" data-index="${index}">
                <span class="m08app-tile-art">
                  ${foodImage(dish)}
                  ${quantity(dish.id) ? `<span class="m08app-tile-qty" aria-label="${quantity(dish.id)} in your order">${icon('check')} ${quantity(dish.id)}</span>` : ''}
                </span>
                <strong>${escape(dish.name)}</strong>
                <span class="m08app-tile-meta"><span>${dish.kcal === null ? '— kcal' : `Est. ${number.format(dish.kcal)} kcal`}</span><span>${price(dish.priceMinor)}</span></span>
              </button>`).join('')}
          </div>` : `<div class="m08app-empty"><span>${icon('meal')}</span><h2>No dishes available</h2></div>`}
        </div>
        ${orderCount() ? `<footer class="m08app-footer m08app-footer--menu">
          <button type="button" class="m08app-primary m08app-primary--total" data-m08-action="order">
            <span>${icon('bag')} View my order <span class="m08app-button-count">${orderCount()}</span></span>
            <span>${price(orderTotal())}</span>
          </button>
        </footer>` : ''}`;
    }

    function nutrition(dish) {
      const values = [
        ['Calories', dish.kcal, 'kcal'],
        ['Carbs', dish.carbs, 'g'],
        ['Fat', dish.fat, 'g'],
        ['Protein', dish.protein, 'g']
      ];
      return `<dl class="m08app-nutrition" aria-label="Estimated nutrition per serving">${values.map(([label, value, unit]) => `
        <div><dt>${label}</dt><dd>${value === null ? '—' : `${number.format(value)} ${unit}`}</dd></div>`).join('')}</dl>
        <p class="m08app-nutrition-note">Estimates per serving</p>`;
    }

    function recommendationCard({ type, url, author, platform, title, excerpt, thumbnail }) {
      const link = `href="${escape(url)}" data-recommendation target="_blank" rel="noopener noreferrer"`;
      const image = thumbnail ? `<img src="${escape(thumbnail)}" alt="" draggable="false">` : '';
      return type === 'video'
        ? `<article class="m08app-video">
            <a class="m08app-video-card" ${link} aria-label="Watch ${escape(author)}’s recommendation on ${escape(platform)}">
              ${image}
              <span class="m08app-video-platform">${escape(platform)}</span>
              <span class="m08app-play">${icon('play')}</span>
              ${title ? `<span class="m08app-video-title">${escape(title)}</span>` : ''}
              <span class="m08app-creator">${escape(author)}</span>
            </a>
          </article>`
        : `<article class="m08app-video m08app-recommendation-post">
            <a class="m08app-post-card" ${link}>
              ${image ? `<span class="m08app-post-art">${image}</span>` : ''}
              <span class="m08app-post-source"><span>${escape(platform)}</span>${icon('external')}</span>
              <span class="m08app-post-copy">
                ${title ? `<strong class="m08app-post-title">${escape(title)}</strong>` : ''}
                ${excerpt ? `<span class="m08app-post-excerpt">${escape(excerpt)}</span>` : ''}
                <span class="m08app-post-creator">${escape(author)}</span>
              </span>
            </a>
          </article>`;
    }

    function community(dish) {
      if (!venue.placeId) return '';
      const items = dish.recommendations
        .filter(item => item?.verified === true && item.restaurantPlaceId === venue.placeId && item.dishId === dish.id)
        .map(item => ({
          type: item.type === 'video' ? 'video' : 'post',
          url: originalUrl(item.url),
          author: text(item.author),
          platform: text(item.platform),
          title: text(item.title),
          excerpt: text(item.excerpt),
          thumbnail: localImage(item.thumbnail),
        }))
        .filter(item => item.url && item.author && item.platform && (item.title || item.excerpt || item.thumbnail));
      if (!items.length) return '';
      return `<section class="m08app-community">
        <h2>They tried it too</h2>
        <div class="m08app-video-strip" role="group" aria-label="Dish recommendations">${items.map(recommendationCard).join('')}</div>
      </section>`;
    }

    function detail() {
      const dish = dishes[dishIndex];
      const rank = calorieRank.indexOf(dish);
      const calorieLabel = calorieRank.length > 2 && calorieRank.length === dishes.length
        ? ['Lowest kcal', 'Lower kcal'][rank] : '';
      const added = quantity(dish.id) > 0;
      function neighbor(direction, offset) {
        if (dishes.length < 2) return '';
        const nextDish = dishes[(dishIndex + offset + dishes.length) % dishes.length];
        return `<button type="button" class="m08app-neighbor m08app-neighbor--${direction}" data-m08-action="${direction}"
          aria-label="View ${escape(nextDish.name)}">${foodImage(nextDish, '', '', 'back')}</button>`;
      }
      return `<header class="m08app-header m08app-header--detail">
          ${orderCount() ? orderButton() : ''}
          <button type="button" class="m08app-icon-button m08app-detail-close" data-m08-action="browse" aria-label="Close dish details">${icon('close')}</button>
        </header>
        <div class="m08app-gallery" role="group" aria-label="Browse dishes" tabindex="0">
          ${neighbor('prev', -1)}
          ${foodImage(dish, 'm08app-plate', dish.name)}
          ${neighbor('next', 1)}
        </div>
        <div class="m08app-detail-card">
          <div class="m08app-detail-scroll">
            <div class="m08app-dish-heading"><h1>${escape(dish.name)}</h1><p>${price(dish.priceMinor)}</p></div>
            ${calorieLabel ? `<div class="m08app-tags m08app-tags--detail"><span class="m08app-calorie-tag">${icon('sparkle')}${calorieLabel}</span></div>` : ''}
            <div class="m08app-nutrition-block">${nutrition(dish)}</div>
            ${community(dish)}
            <section class="m08app-ingredients"><h2>What’s in it</h2><p>${escape(dish.ingredients.join(', ')) || '—'}</p></section>
          </div>
          <footer class="m08app-footer m08app-footer--detail">
            <button type="button" class="m08app-secondary" data-m08-action="browse">Keep browsing</button>
            <button type="button" class="m08app-primary" data-m08-action="${added ? 'view-added' : 'add'}">
              ${icon(added ? 'check' : 'plus')} ${added ? 'Added to my order' : 'Add to my order'}
            </button>
          </footer>
        </div>`;
    }

    function orderRow(dish, staff) {
      const count = quantity(dish.id);
      const total = price(dish.priceMinor === null ? null : dish.priceMinor * count);
      return `<li class="m08app-order-row">
        ${staff ? '' : `<div class="m08app-order-art">${foodImage(dish)}</div>`}
        <div class="m08app-order-dish"><strong>${staff ? `${count} × ` : ''}${escape(dish.name)}</strong>
          ${staff ? '' : `<small>${price(dish.priceMinor)} each · ${total} subtotal</small>`}
        </div>
        ${staff ? `<span class="m08app-line-total">${total}</span>` : `
          <div class="m08app-stepper" aria-label="Quantity for ${escape(dish.name)}">
            <button type="button" data-m08-action="decrement" data-id="${escape(dish.id)}" aria-label="Remove one ${escape(dish.name)}">${icon('minus')}</button>
            <span aria-label="Quantity">${count}</span>
            <button type="button" data-m08-action="increment" data-id="${escape(dish.id)}" aria-label="Add one ${escape(dish.name)}">${icon('plus')}</button>
          </div>`}
      </li>`;
    }

    function order(staff) {
      const count = orderCount();
      const total = `<div class="m08app-subtotal"><span>Subtotal</span><strong>${price(orderTotal())}</strong></div>`;
      return `${header(staff ? 'Order summary' : 'My order', staff ? 'order' : 'order-back', staff ? 'Back to my order' : 'Back')}
        <div class="m08app-order-scroll">
          ${staff ? `<div class="m08app-menu-heading"><h1>${escape(venueName)}</h1><p>Show this screen to your server.</p></div>` : ''}
          ${count ? `<ul class="m08app-order-list">${orderLines().map(dish => orderRow(dish, staff)).join('')}</ul>${total}`
            : `<div class="m08app-empty"><span>${icon('bag')}</span><h1>Your order is empty</h1>
                <button type="button" class="m08app-primary" data-m08-action="browse">Browse menu</button></div>`}
        </div>
        ${count && !staff ? `<footer class="m08app-footer m08app-footer--order">
          <button type="button" class="m08app-primary" data-m08-action="staff">Show to staff</button>
        </footer>` : ''}
        ${count && staff ? `<footer class="m08app-footer m08app-footer--order m08app-footer--log">
          ${finishError ? '<p class="m08app-status" role="alert">Couldn’t save your meal. Your order is still here.</p>' : '<p>Log the meal after you’ve eaten.</p>'}
          <button type="button" class="m08app-primary" data-m08-action="finish-order">Finish &amp; log meal</button>
        </footer>` : ''}`;
    }

    function render(focusAction, focusId) {
      app.className = `m08app m08app--${view}`;
      if (view === 'detail') app.innerHTML = detail();
      else if (view === 'order' || view === 'staff') app.innerHTML = order(view === 'staff');
      else if (view === 'intake') app.innerHTML = renderHealth(state.meals, intakeView);
      else app.innerHTML = menu();
      const scroll = app.querySelector('.m08app-menu-scroll');
      if (scroll) scroll.scrollTop = menuScroll;
      if (focusAction) {
        const button = [...app.querySelectorAll('[data-m08-action]')].find(element => (
          element.dataset.m08Action === focusAction &&
          (focusAction !== 'dish' || Number(element.dataset.index) === dishIndex) &&
          (!focusId || element.dataset.id === focusId)
        ));
        (button ?? app.querySelector('.m08app-header button'))?.focus({ preventScroll: true });
      }
    }

    function navigate(target, focusAction, focusId) {
      if (view === 'menu') menuScroll = app.querySelector('.m08app-menu-scroll').scrollTop;
      view = target;
      finishError = false;
      render(focusAction, focusId);
    }

    function step(direction, focusAction) {
      if (dishes.length < 2) return;
      dishIndex = (dishIndex + direction + dishes.length) % dishes.length;
      render(focusAction);
    }

    function openIntake() {
      intakeView = 'today';
      navigate('intake', 'intake-back');
    }

    function finishOrder() {
      if (view !== 'staff' || !orderCount()) return;
      const items = orderLines().map(({ id, name, image, kcal, carbs, fat, protein }) => ({
        id, name, image, kcal, carbs, fat, protein, quantity: quantity(id),
      }));
      const meals = [...state.meals, { recordedAt: new Date().toISOString(), items }];
      // Persist the meal and clear its order in one write so a failed log leaves the order intact.
      if (!writeState(storageKey, new Map(), meals)) {
        finishError = true;
        render('finish-order');
        return;
      }
      state.meals = meals;
      cart.clear();
      returnView = 'menu';
      openIntake();
    }

    app.addEventListener('click', event => {
      const recommendation = event.target.closest('a[data-recommendation]');
      if (recommendation && bridge) {
        event.preventDefault();
        bridge.openRecommendation(recommendation.getAttribute('href'));
        return;
      }
      const button = event.target.closest('[data-m08-action]');
      if (!button) return;
      const { m08Action: action, index, id } = button.dataset;
      switch (action) {
        case 'browse':
          navigate('menu', 'dish');
          break;
        case 'dish':
          dishIndex = Number(index);
          navigate('detail', 'browse');
          break;
        case 'prev':
        case 'next':
          if (performance.now() >= ignoreGalleryClickUntil) step(action === 'prev' ? -1 : 1, action);
          break;
        case 'add':
          changeQuantity(dishes[dishIndex].id, 1);
          render('view-added');
          break;
        case 'view-added':
        case 'order':
          if (view !== 'staff') returnView = view;
          navigate('order', 'order-back');
          break;
        case 'order-back':
          navigate(returnView, 'order');
          break;
        case 'increment':
        case 'decrement':
          changeQuantity(id, action === 'increment' ? 1 : -1);
          render(action, id);
          break;
        case 'staff':
          navigate('staff', 'order');
          break;
        case 'finish-order':
          finishOrder();
          break;
        case 'intake':
          openIntake();
          break;
        case 'intake-back':
          navigate('menu', 'intake');
          break;
        case 'intake-today':
        case 'intake-history':
          intakeView = action === 'intake-today' ? 'today' : 'history';
          render(action);
          break;
      }
    });

    app.addEventListener('keydown', event => {
      if (view !== 'detail' || !event.target.closest('.m08app-gallery')) return;
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault();
        step(event.key === 'ArrowLeft' ? -1 : 1);
        app.querySelector('.m08app-gallery').focus({ preventScroll: true });
      }
    });

    app.addEventListener('pointerdown', event => {
      if (event.button === 0 && event.target.closest('.m08app-gallery')) {
        swipeStart = { x: event.clientX, y: event.clientY, id: event.pointerId };
      }
    });
    app.addEventListener('pointerup', event => {
      const start = swipeStart;
      swipeStart = null;
      if (view !== 'detail' || !start || start.id !== event.pointerId) return;
      const dx = event.clientX - start.x;
      const dy = event.clientY - start.y;
      if (Math.abs(dx) > Math.max(30, Math.abs(dy) * 1.25)) {
        ignoreGalleryClickUntil = performance.now() + 250;
        step(dx < 0 ? 1 : -1);
      }
    });
    app.addEventListener('pointercancel', () => { swipeStart = null; });

    render();
  }

  mount(document.getElementById('menu-app'), window.MenuApp);
})();
