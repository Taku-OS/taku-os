import assert from 'node:assert/strict';
import test from 'node:test';
import {matchPublishedMenu} from './menuEvidence.ts';
import {parseMenuDishes} from './menuScan.ts';

const line = (text, left, top, right, bottom) => ({text, box: {left, top, right, bottom}, words: []});
const scan = (lines) => parseMenuDishes({width: 1000, height: 600, lines});

test('keeps split prices on the nearest dish row and preserves original pixels and price labels', () => {
  const dishes = scan([
    line('12', 904, 98, 932, 118),
    line('Lunch', 30, 10, 115, 33),
    line('Mushroom Salad', 30, 52, 240, 72),
    line('mixed greens, roasted mushrooms', 30, 74, 380, 90),
    line('Chickpea Bowl', 30, 99, 230, 119),
    line('8.5', 901, 54, 932, 71),
    line('Add chicken 4', 30, 139, 215, 159),
    line('17', 904, 144, 932, 164),
    line('Tomato Soup … $ 6.75', 30, 174, 270, 196),
  ]);

  assert.deepEqual(dishes.map(({name, price}) => [name, price]), [
    ['Mushroom Salad', '8.5'], ['Chickpea Bowl', '12'], ['Tomato Soup', '$ 6.75'],
  ]);
  assert.deepEqual(dishes[0].box, {left: 30, top: 52, right: 932, bottom: 72});
  assert.equal(dishes[0].description, 'mixed greens, roasted mushrooms');
});

test('keeps an orphan price from crossing a second column or intervening OCR text', () => {
  const dishes = scan([
    line('Corn Salad', 30, 50, 190, 70), line('12', 374, 50, 400, 70),
    line('Grilled Cod', 535, 50, 700, 70), line('25', 899, 51, 930, 71),
    line('Bean Salad', 30, 100, 185, 120), line('11', 374, 100, 400, 120),
    line('Unpriced Dish', 30, 150, 250, 170), line('22', 899, 150, 930, 170),
    line('Unpriced Soup', 30, 200, 180, 220), line('low sodium, dairy-free', 190, 201, 365, 220),
    line('10', 374, 201, 400, 221),
    line('Lunch Special 640', 30, 250, 275, 270),
  ]);

  assert.deepEqual(dishes.map(({name, price}) => [name, price]), [
    ['Corn Salad', '12'], ['Grilled Cod', '25'], ['Bean Salad', '11'],
  ]);
});

test('does not treat a choice between two prices as a new dish', () => {
  const dishes = scan([
    line('STEAK TARTARE', 300, 50, 500, 70),
    line('cornichons, grilled baguette', 280, 75, 550, 93),
    line('appetizer 19/ main 30', 280, 98, 520, 118),
    line('7 Grain Salad 21', 300, 135, 520, 155),
  ]);

  assert.deepEqual(dishes.map(({name, price}) => [name, price]), [['7 Grain Salad', '21']]);
});

test('offers browse picks for actual distinct scan results when the menu has no published recommendations', () => {
  const names = ['Mushroom Salad', 'Chickpea Bowl', 'Tomato Soup', 'Grilled Cod', 'Bean Salad', 'Mushroom Salad'];
  for (const [rows, expected] of [[0, 0], [2, 2], [4, 4], [6, 5]]) {
    const dishes = scan(names.slice(0, rows).map((name, index) => line(`${name} $12`, 30, 55 + index * 45, 325, 75 + index * 45)));
    const matched = matchPublishedMenu(dishes);
    const picks = matched.dishes.filter(dish => dish.suggestions?.picks);
    assert.equal(matched.venue, undefined);
    assert.equal(picks.length, expected);
    assert.equal(new Set(picks.map(dish => dish.name)).size, expected);
    assert.ok(picks.every(dish => dish.suggestions.picks.en === 'Explore' && !dish.evidence));
  }
});
