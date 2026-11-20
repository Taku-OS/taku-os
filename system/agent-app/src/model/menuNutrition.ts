type LocalizedText = {en: string; zh: string};
type Range = {min: number; max: number};
type Nutrient = 'calories' | 'carbs' | 'fat' | 'protein';

type IngredientAssumption = {
  ingredient: LocalizedText;
  grams: Range;
  sourceUrl: string;
  portionSourceUrl: string;
  portionBasis: LocalizedText;
};

export type MenuNutrition = Record<Nutrient, Range> & {
  kind: 'estimated' | 'official';
  sourceUrl: string;
  scope?: 'piece';
  note?: LocalizedText;
  assumptions?: readonly IngredientAssumption[];
};

type RecipeIngredient = IngredientAssumption & {per100g: Record<Nutrient, number>};

export function menuNutritionValue(nutrition: MenuNutrition | undefined, nutrient: Nutrient): string {
  if (!nutrition) return '';
  const {min, max} = nutrition[nutrient];
  if (min === max) return String(min);
  const increment = nutrient === 'calories' ? 10 : 1;
  return String(Math.round((min + max) / (2 * increment)) * increment);
}

const foodDataCentral = 'https://fdc.nal.usda.gov';
const usdaFood = (id: number) => `${foodDataCentral}/portal-data/external/${id}`;
const lunetteMenu = 'https://www.cafelunette.com/bistro-menu/';
const lunetteServing = {
  en: 'Estimated from the menu ingredients and typical portions. The restaurant’s serving size and recipe may vary.',
  zh: '按菜单食材和常见份量估算，实际份量和做法以餐厅为准。',
};
const lunetteSandwich = {
  en: 'Estimated for the sandwich itself. Side greens, fries or soup are separate.',
  zh: '仅估算三明治本身，配菜沙拉、薯条或汤另计。',
};

export const chickenjoyNutrition: MenuNutrition = {
  kind: 'estimated',
  scope: 'piece',
  sourceUrl: 'https://jollibee-prod-media.s3.us-west-2.amazonaws.com/20260701_JB_USA_Nutrition_Facts_2026_Mktg_8f037d9d5d.pdf',
  note: {
    en: 'Based on Jollibee USA’s original and spicy drumstick and thigh data. Singapore portions may vary. Gravy and sides are excluded.',
    zh: '参考 Jollibee 美国菜单的原味和辣味鸡小腿、鸡大腿数据。新加坡门店的份量可能不同，估算未计入肉汁和配菜。',
  },
  calories: {min: 220, max: 380},
  carbs: {min: 3, max: 15},
  fat: {min: 14, max: 28},
  protein: {min: 16, max: 27},
};

function ingredient(
  name: LocalizedText,
  [min, max]: readonly [number, number],
  source: number | string,
  [calories, carbs, fat, protein]: readonly [number, number, number, number],
  portionSourceUrl: string,
  portionBasis: LocalizedText,
): RecipeIngredient {
  return {
    ingredient: name,
    grams: {min, max},
    sourceUrl: typeof source === 'number' ? usdaFood(source) : source,
    portionSourceUrl,
    portionBasis,
    per100g: {calories, carbs, fat, protein},
  };
}

function estimate(ingredients: readonly RecipeIngredient[], note = lunetteServing): MenuNutrition {
  function perServing(nutrient: Nutrient): Range {
    const min = ingredients.reduce((sum, item) => sum + item.grams.min * item.per100g[nutrient] / 100, 0);
    const max = ingredients.reduce((sum, item) => sum + item.grams.max * item.per100g[nutrient] / 100, 0);
    const increment = nutrient === 'calories' ? 10 : 1;
    return {min: Math.floor(min / increment) * increment, max: Math.ceil(max / increment) * increment};
  }

  return {
    kind: 'estimated',
    sourceUrl: `${foodDataCentral}/download-datasets/`,
    note,
    calories: perServing('calories'),
    carbs: perServing('carbs'),
    fat: perServing('fat'),
    protein: perServing('protein'),
    assumptions: ingredients.map(({ingredient, grams, sourceUrl, portionSourceUrl, portionBasis}) =>
      ({ingredient, grams, sourceUrl, portionSourceUrl, portionBasis})),
  };
}

const katzSandwichPortion = 'https://www.dailymotion.com/video/x9w8n7a';
const katzSandwichPortionBasis = {
  en: 'Katz’s owner describes a 12 oz sandwich at the counter. Assumes 250–340 g of meat to allow for different hand-carved portions.',
  zh: '店主在采访中提到柜台三明治约 12 盎司。考虑手切份量不同，按 250–340 克肉估算。',
};
const katzRye = ingredient({en: 'Deli rye bread', zh: '黑麦面包'}, [64, 86], 2707755, [259, 48.3, 3.3, 8.5], usdaFood(2707755), {
  en: 'Assumes two slices of rye. USDA lists 32 g for a regular slice and 43 g for a thick slice.',
  zh: '按两片黑麦面包估算。USDA 的普通切片重 32 克，厚片重 43 克。',
});
const katzMustard = ingredient({en: 'Deli mustard (yellow mustard as reference)', zh: '店家芥末酱（以黄芥末酱估算）'}, [0, 10], 172234, [60, 5.83, 3.34, 3.74], 'https://katzsdelicatessen.com/deli-mustard-0-5-lb.html', {
  en: 'Assumes up to two teaspoons of optional mustard. USDA lists 5 g per teaspoon.',
  zh: '芥末酱可选，按最多两茶匙估算。USDA 的黄芥末酱每茶匙重 5 克。',
});
const katzSandwich = {
  en: 'Estimated for one whole rye sandwich using reference ingredients and an assumed meat portion. Pickles, sides and extra toppings are separate.',
  zh: '按一整个黑麦三明治估算，采用参考食材数据和推算的肉量。腌黄瓜、配菜和加料另计。',
};

export const katzNutrition = {
  'katzs pastrami': estimate([
    ingredient({en: 'Smoked beef navel (Boar’s Head pastrami navel as reference)', zh: '烟熏牛腹肉（参考 Boar’s Head 同部位烟熏牛肉）'}, [250, 340], 'https://boarshead.com/products/detail/341-boars-head-pastrami-navel/nutrition', [200 * 100 / 56, 0, 18 * 100 / 56, 9 * 100 / 56], katzSandwichPortion, katzSandwichPortionBasis),
    katzRye,
    katzMustard,
  ], katzSandwich),
  'katzs corned beef': estimate([
    ingredient({en: 'Cooked corned beef brisket', zh: '熟腌牛胸肉'}, [250, 340], 170200, [251, 0.47, 18.98, 18.17], katzSandwichPortion, katzSandwichPortionBasis),
    katzRye,
    katzMustard,
  ], katzSandwich),
  'katzs brisket': estimate([
    ingredient({en: 'Slow-roasted brisket (braised brisket as reference)', zh: '慢烤牛胸肉（以炖牛胸肉估算）'}, [250, 340], 168665, [331, 0, 24.5, 25.8], katzSandwichPortion, katzSandwichPortionBasis),
    katzRye,
    ingredient({en: 'Gravy (prepared beef gravy as reference)', zh: '肉汁（以即食牛肉汁估算）'}, [0, 30], 171564, [53, 4.81, 2.36, 3.75], 'https://katzsdelicatessen.com/sliced-brisket-by-the-pound.html', {
      en: 'Katz’s roasts the brisket in gravy. Assumes up to two tablespoons of gravy on the meat and excludes an extra serving on the side.',
      zh: 'Katz’s 用肉汁烤牛胸肉。按肉上最多两汤匙肉汁估算，另点的蘸汁另计。',
    }),
  ], katzSandwich),
};

const soupRecipe = 'https://www.foodnetwork.com/recipes/big-french-onion-soup-18774401';
const soupKitchenRecipe = 'https://www.foodnetwork.com/recipes/food-network-kitchen/french-onion-soup-3361775';
const peachSaladRecipe = 'https://www.foodnetwork.com/recipes/peach-salad-with-tomatoes-feta-and-basil-vinaigrette-2124903';
const prosciuttoSaladRecipe = 'https://cookingwithcocktailrings.com/peach-tomato-mozzarella-salad-with-crispy-prosciutto/';
const caperSaladRecipe = 'https://www.donnahay.com.au/recipes/recipe-entertaining/sides/heirloom-tomato-salad-with-crunchy-kale-and-capers';
const tomatoCarpaccioRecipe = 'https://www.foodnetwork.com/recipes/ina-garten/tomato-carpaccio-3559842';
const cheeseCatalogue = 'https://www.agropursolutions.ca/sites/agropursolutions/files/inline-images/AGP22-001_-_CatalogueFSAgropur_8%2C5X11_ONTARIO_ANG_FINAL%202022-05-05.pdf';
const liverParfaitRecipe = 'https://www.bbcgoodfood.com/recipes/8190/chicken-liver-parfaits';
const tunaMeltRecipe = 'https://spendsmart.extension.iastate.edu/recipe/tuna-melt-sandwich/';
const avocadoToastRecipe = 'https://eat-move-save.extension.illinois.edu/eat/recipes/avocado-toast';
const hempHearts = 'https://manitobaharvest.ca/products/natural-hemp-hearts';
const roastBeefRecipe = 'https://www.pepperidgefarm.com/recipe/roast-beef-with-onion-avocado-sandwiches/';
const croissantBltRecipe = 'https://seasoned.com/blog/2024/11/blt-croissant-sandwiches';
const croqueMadameRecipe = 'https://www.epicurious.com/recipes/food/views/croque-madame-237589';

const fruitChutney = ingredient({en: 'Fruit chutney (apricot preserves as reference)', zh: '水果酸甜酱（以杏果酱估算）'}, [15, 30], 170645, [242, 64.4, 0.2, 0.7], usdaFood(170645), {
  en: 'Assumes a small scoop of about ¾–1½ tablespoons. USDA lists 20 g per tablespoon of apricot preserves.',
  zh: '按一小勺约 ¾–1½ 汤匙估算。USDA 的杏果酱每汤匙重 20 克。',
});
const butterCroissant = ingredient({en: 'Butter croissant', zh: '黄油可颂'}, [57, 67], 174987, [406, 45.8, 21, 8.2], usdaFood(174987), {
  en: 'Assumes one medium to large croissant, using USDA portion weights.',
  zh: '按 USDA 的份量，估算为一个中等至大型可颂。',
});
const cookedBacon = ingredient({en: 'Cooked bacon', zh: '熟培根'}, [24, 35], 167914, [548, 1.35, 43.27, 35.73], croissantBltRecipe, {
  en: 'The reference sandwich uses three thick-cut slices. USDA lists 8.1 g for a regular cooked slice.',
  zh: '参考三明治用三片厚切培根。USDA 的一片普通熟培根重 8.1 克。',
});
const arugula = ingredient({en: 'Arugula', zh: '芝麻菜'}, [5, 15], 169387, [25, 3.65, 0.66, 2.58], usdaFood(169387), {
  en: 'Assumes a small handful. USDA lists 10 g per cup of arugula.',
  zh: '按一小把估算。USDA 的芝麻菜每杯重 10 克。',
});
const dijonnaise = ingredient({en: 'Dijonnaise (mayonnaise as reference)', zh: '第戎芥末蛋黄酱（以蛋黄酱估算）'}, [10, 20], 171009, [680, 0.57, 74.85, 0.96], croissantBltRecipe, {
  en: 'The reference croissant uses one tablespoon of mayonnaise. USDA lists 13.8 g per tablespoon.',
  zh: '参考可颂用一汤匙蛋黄酱。USDA 的蛋黄酱每汤匙重 13.8 克。',
});
const sandwichBread = ingredient({en: 'Sandwich bread (white bread as reference)', zh: '三明治面包（以白面包估算）'}, [55, 85], 174924, [266, 49.42, 3.33, 8.85], usdaFood(174924), {
  en: 'Assumes the equivalent of two regular to thick slices. USDA lists about 29 g per regular slice.',
  zh: '按两片普通至厚切面包估算。USDA 的普通切片每片约 29 克。',
});
const pickledRedOnion = ingredient({en: 'Pickled red onion (raw onion as reference)', zh: '腌红洋葱（以生洋葱估算）'}, [9, 18], 170000, [40, 9.34, 0.1, 1.1], usdaFood(170000), {
  en: 'Assumes one or two thin slices. USDA lists 9 g per thin slice, before pickling.',
  zh: '按一至两片估算。USDA 的生洋葱薄片每片重 9 克。',
});
const sandwichCapers = ingredient({en: 'Drained capers', zh: '沥干的酸豆'}, [3, 6], 172238, [23, 4.89, 0.86, 2.36], usdaFood(172238), {
  en: 'Assumes about one or two teaspoons. USDA lists 8.6 g per drained tablespoon.',
  zh: '按约一至两茶匙估算。USDA 的沥干酸豆每汤匙重 8.6 克。',
});
const mortadella = ingredient({en: 'Shaved mortadella', zh: '薄切莫塔德拉香肠'}, [45, 80], 174573, [311, 3.05, 25.39, 16.37], usdaFood(174573), {
  en: 'Assumes the equivalent of about three to five standard 15 g deli slices.',
  zh: '按约三至五片常规切片估算，每片重 15 克。',
});
const brie = ingredient({en: 'Brie', zh: '布里芝士'}, [25, 45], 172177, [334, 0.45, 27.68, 20.75], usdaFood(172177), {
  en: 'Assumes about 1–1½ oz of sliced Brie.',
  zh: '按约 1–1½ 盎司切片布里芝士估算。',
});
const friedEgg = ingredient({en: 'Fried egg', zh: '煎蛋'}, [46, 54], 173423, [196, 0.83, 14.84, 13.61], usdaFood(173423), {
  en: 'Assumes one large egg. USDA lists 46 g for a large fried egg.',
  zh: '按一个大号煎蛋估算。USDA 的大号煎蛋重 46 克。',
});

// The menu names the ingredients but gives no weights. Comparable recipes and USDA portions bracket one serving.
export const lunetteNutrition = {
  'baked camembert': estimate([
    ingredient({en: 'Camembert, half a wheel', zh: '卡芒贝尔芝士（半轮）'}, [85, 125], 172178, [300, 0.46, 24.26, 19.8], cheeseCatalogue, {
      en: 'The menu serves half a wheel. The food-service catalogue lists 170 g and 250 g wheels.',
      zh: '菜单标明每份半轮。餐饮供应目录列有 170 克和 250 克的整轮芝士。',
    }),
    ingredient({en: 'Griddled baguette', zh: '烤法棍'}, [58, 87], 174911, [319, 61.93, 2.14, 13], usdaFood(174911), {
      en: 'Assumes two or three small slices of toasted French bread. USDA lists 29 g per small slice.',
      zh: '按两至三小片烤法棍估算。USDA 的小片烤法式面包每片重 29 克。',
    }),
    fruitChutney,
  ], {
    en: 'Estimated for the whole plate of half a cheese wheel, chutney and baguette.',
    zh: '按半轮芝士、果酱和法棍整盘估算。',
  }),
  'french onion soup': estimate([
    ingredient({en: 'Onion (raw weight)', zh: '洋葱（生重）'}, [110, 140], 170000, [40, 9.34, 0.1, 1.1], soupRecipe, {
      en: 'About 1 to 1¼ medium onions. USDA lists 110 g per medium onion.',
      zh: '约 1 至 1¼ 个中等洋葱。USDA 的中等洋葱每个重 110 克。',
    }),
    ingredient({en: 'Beef broth (homemade stock as reference)', zh: '牛肉汤底（以自制牛高汤估算）'}, [220, 250], 172883, [13, 1.2, 0.09, 1.97], soupRecipe, {
      en: 'About one cup of broth. USDA lists 240 g per cup of homemade beef stock.',
      zh: '约一杯汤底。USDA 的自制牛高汤每杯重 240 克。',
    }),
    ingredient({en: 'Gruyère', zh: '格鲁耶尔芝士'}, [28, 44], 171242, [413, 0.36, 32.34, 29.81], soupRecipe, {
      en: 'The reference recipe uses 115–175 g of grated cheese for four bowls.',
      zh: '参考食谱用 115–175 克碎芝士做四碗。',
    }),
    ingredient({en: 'Croutons (toasted French bread)', zh: '烤面包丁（以烤法棍估算）'}, [20, 45], 174911, [319, 61.93, 2.14, 13], soupKitchenRecipe, {
      en: 'About one slice of toasted baguette. USDA lists 29 g for a small slice of toasted French bread.',
      zh: '约一片烤法棍。USDA 的小片烤法式面包每片重 29 克。',
    }),
    ingredient({en: 'Butter (assumed for cooking)', zh: '黄油（按炒洋葱估算）'}, [7, 12], 173430, [717, 0.06, 81.11, 0.85], soupRecipe, {
      en: 'The reference recipe uses ¾ tablespoon per bowl, about 11 g using USDA portion weights.',
      zh: '参考食谱每碗用 ¾ 汤匙黄油，按 USDA 的份量换算约 11 克。',
    }),
  ]),
  'salade de tomates': estimate([
    ingredient({en: 'Tomato', zh: '番茄'}, [110, 190], 170457, [18, 3.89, 0.2, 0.88], peachSaladRecipe, {
      en: 'The reference salad uses about 113 g per plate. The upper end allows for a large tomato (182 g in USDA).',
      zh: '参考沙拉每份用约 113 克番茄。上限接近 USDA 的一个大番茄（182 克）。',
    }),
    ingredient({en: 'Peach', zh: '桃子'}, [75, 125], 169928, [39, 9.54, 0.25, 0.91], peachSaladRecipe, {
      en: 'About half to five-sixths of a medium peach. The reference recipe uses 4–5 peaches for six plates.',
      zh: '约半个至六分之五个中等桃子。参考食谱用 4–5 个桃子做六份。',
    }),
    ingredient({en: 'Drained capers', zh: '沥干的酸豆'}, [4, 7], 172238, [23, 4.89, 0.86, 2.36], caperSaladRecipe, {
      en: 'The reference tomato salad uses 50 g of capers for 8–10 plates.',
      zh: '参考番茄沙拉用 50 克酸豆做 8–10 份。',
    }),
    ingredient({en: 'Parmesan', zh: '帕玛森芝士'}, [4, 15], 170848, [392, 3.22, 25, 35.75], tomatoCarpaccioRecipe, {
      en: 'About 4 g for a light garnish in the caper salad, up to ½ ounce per plate in the reference carpaccio.',
      zh: '参考酸豆番茄沙拉的少量芝士约 4 克，参考番茄薄片食谱每份约用半盎司。',
    }),
    ingredient({en: 'Prosciutto (before crisping)', zh: '意式火腿（烤脆前）'}, [9, 22], 2705879, [195, 0.3, 8.32, 27.8], prosciuttoSaladRecipe, {
      en: 'One USDA thin slice is 9.2 g. The reference salad crisps about 21 g per plate.',
      zh: 'USDA 的一片薄火腿重 9.2 克。参考食谱每份烤约 21 克火腿。',
    }),
    ingredient({en: 'Olive oil in dressing', zh: '油醋汁里的橄榄油'}, [9, 14], 171413, [884, 0, 100, 0], peachSaladRecipe, {
      en: 'About 2–3 teaspoons per plate. The reference dressing uses ¼ cup of oil for six plates.',
      zh: '每份约 2–3 茶匙。参考食谱用 ¼ 杯橄榄油调六份酱汁。',
    }),
    ingredient({en: 'Vinegar in dressing (red wine vinegar assumed)', zh: '油醋汁里的醋（以红酒醋估算）'}, [4, 10], 172240, [19, 0.27, 0, 0.04], peachSaladRecipe, {
      en: 'About 1–2 teaspoons per plate. The reference dressing uses ¼ cup of sherry vinegar for six plates.',
      zh: '每份约 1–2 茶匙。参考食谱用 ¼ 杯雪莉醋调六份酱汁。',
    }),
    ingredient({en: 'Fresh basil in dressing', zh: '油醋汁里的新鲜罗勒'}, [1, 5], 172232, [23, 2.65, 0.64, 3.15], prosciuttoSaladRecipe, {
      en: 'Up to about one tablespoon chopped. The reference dressing uses ¼ packed cup of leaves for four plates.',
      zh: '切碎后约不超过一汤匙。参考食谱用 ¼ 杯压实的罗勒叶调四份酱汁。',
    }),
  ]),
  'brasserie caesar': estimate([
    ingredient({en: 'Kale', zh: '羽衣甘蓝'}, [50, 85], 168421, [35, 4.42, 1.49, 2.92], lunetteMenu, {
      en: 'The lunch menu lists kale. Assumes about 2½–4 loosely filled cups before dressing, using USDA’s 21 g per cup.',
      zh: '午餐菜单使用羽衣甘蓝。按拌酱前约 2½–4 杯估算，USDA 的羽衣甘蓝每杯重 21 克。',
    }),
    ingredient({en: 'Bacon lardons (cooked bacon as reference)', zh: '培根丁（以熟培根估算）'}, [16, 30], 167914, [548, 1.35, 43.27, 35.73], usdaFood(167914), {
      en: 'Assumes the equivalent of two to four regular cooked slices cut into lardons.',
      zh: '按约两至四片普通熟培根切丁估算。',
    }),
    ingredient({en: 'Seasoned croutons', zh: '调味烤面包丁'}, [12, 25], 172752, [465, 63.5, 18.3, 10.8], usdaFood(172752), {
      en: 'Assumes roughly a third to two-thirds of a cup. USDA lists 40 g per cup.',
      zh: '按约三分之一至三分之二杯估算。USDA 的调味烤面包丁每杯重 40 克。',
    }),
    ingredient({en: 'Parmesan', zh: '帕玛森芝士'}, [8, 18], 170848, [392, 3.22, 25, 35.75], usdaFood(170848), {
      en: 'Assumes a garnish of about ¼–⅔ oz shaved Parmesan.',
      zh: '按约 ¼–⅔ 盎司的帕玛森芝士薄片估算。',
    }),
    ingredient({en: 'Caesar dressing (assumed)', zh: '凯撒沙拉酱（按常见做法估算）'}, [20, 35], 169055, [542, 3.3, 57.85, 2.17], usdaFood(169055), {
      en: 'Assumes about 1½–2½ tablespoons. USDA lists 14.7 g per tablespoon.',
      zh: '按约 1½–2½ 汤匙估算。USDA 的凯撒沙拉酱每汤匙重 14.7 克。',
    }),
  ]),
  'chicken liver parfait': estimate([
    ingredient({en: 'Chicken liver (raw weight)', zh: '鸡肝（生重）'}, [45, 70], 171060, [119, 0.73, 4.83, 16.92], liverParfaitRecipe, {
      en: 'The reference recipe uses about 67 g of raw chicken liver per ramekin. The lower end allows for a smaller starter portion.',
      zh: '参考食谱每小盅用约 67 克生鸡肝。下限按较小的前菜份量估算。',
    }),
    ingredient({en: 'Butter in the parfait', zh: '鸡肝酱里的黄油'}, [20, 40], 173430, [717, 0.06, 81.11, 0.85], liverParfaitRecipe, {
      en: 'The reference uses about 33 g of butter per ramekin before the clarified-butter topping.',
      zh: '参考食谱每小盅用约 33 克黄油，表面的澄清黄油另加。',
    }),
    ingredient({en: 'Heavy cream in the parfait', zh: '鸡肝酱里的淡奶油'}, [5, 12], 170859, [340, 2.84, 36.08, 2.84], liverParfaitRecipe, {
      en: 'The reference uses about 8 mL of cream per ramekin.',
      zh: '参考食谱每小盅用约 8 毫升奶油。',
    }),
    ingredient({en: 'Crostini (toasted baguette as reference)', zh: '烤面包片（以烤法棍估算）'}, [30, 60], 174911, [319, 61.93, 2.14, 13], usdaFood(174911), {
      en: 'Assumes the equivalent of one or two small slices of toasted French bread.',
      zh: '按一至两小片烤法式面包的份量估算。',
    }),
    fruitChutney,
  ]),
  'tuna melt': estimate([
    sandwichBread,
    ingredient({en: 'Drained light tuna', zh: '沥干的淡色金枪鱼肉'}, [65, 100], 171986, [116, 0, 0.82, 25.51], tunaMeltRecipe, {
      en: 'The reference uses half a 5 oz can per sandwich before draining. The upper end allows for a fuller deli portion.',
      zh: '参考食谱每份用半罐 5 盎司的金枪鱼，罐重为沥干前的重量。上限按馅料较多的份量估算。',
    }),
    ingredient({en: 'Provolone', zh: '普罗沃洛内芝士'}, [20, 40], 170850, [351, 2.14, 26.62, 25.58], usdaFood(170850), {
      en: 'Assumes about one slice, allowing for slice thickness. USDA lists 28 g per slice.',
      zh: '按约一片估算，并计入厚薄差异。USDA 的芝士每片重 28 克。',
    }),
    pickledRedOnion,
    ingredient({en: 'Olives', zh: '橄榄'}, [8, 17], 169094, [116, 6.04, 10.9, 0.84], usdaFood(169094), {
      en: 'Assumes about one or two tablespoons of chopped olives. USDA lists 8.4 g per tablespoon.',
      zh: '按约一至两汤匙切碎的橄榄估算。USDA 的橄榄每汤匙重 8.4 克。',
    }),
    sandwichCapers,
    ingredient({en: 'Cornichons (dill pickles as reference)', zh: '小酸黄瓜（以莳萝腌黄瓜估算）'}, [7, 20], 168558, [12, 2.41, 0.3, 0.5], usdaFood(168558), {
      en: 'Assumes the equivalent of one to three dill pickle slices. USDA lists 7 g per slice.',
      zh: '按一至三片腌黄瓜的份量估算。USDA 的腌黄瓜每片重 7 克。',
    }),
    ingredient({en: 'Mayonnaise in the tuna filling (assumed)', zh: '金枪鱼馅里的蛋黄酱（按常见做法估算）'}, [8, 18], 171009, [680, 0.57, 74.85, 0.96], tunaMeltRecipe, {
      en: 'The reference uses ¾ tablespoon per sandwich. USDA lists 13.8 g per tablespoon of regular mayonnaise.',
      zh: '参考食谱每份用 ¾ 汤匙。USDA 的普通蛋黄酱每汤匙重 13.8 克。',
    }),
  ], lunetteSandwich),
  'smoked gouda chicken': estimate([
    sandwichBread,
    ingredient({en: 'Roasted chicken breast (assumed cut)', zh: '烤鸡胸肉（以鸡胸估算）'}, [85, 130], 171477, [165, 0, 3.57, 31.02], usdaFood(171477), {
      en: 'USDA lists 86 g for one cooked skinless breast. The upper end allows for a larger portion of sandwich filling.',
      zh: 'USDA 的一块去皮熟鸡胸重 86 克。上限按馅料较多的份量估算。',
    }),
    ingredient({en: 'Smoked Gouda (Gouda as reference)', zh: '烟熏高达芝士（以高达芝士估算）'}, [25, 40], 171241, [356, 2.22, 27.44, 24.94], usdaFood(171241), {
      en: 'Assumes about 1–1½ oz of Gouda. USDA lists 28.35 g per ounce.',
      zh: '按约 1–1½ 盎司高达芝士估算。USDA 每盎司折合 28.35 克。',
    }),
    ingredient({en: 'Oil-packed sun-dried tomatoes, drained', zh: '沥干的油浸晒干番茄'}, [9, 21], 169384, [213, 23.33, 14.08, 5.06], usdaFood(169384), {
      en: 'Assumes about three to seven pieces. USDA lists 3 g per drained piece.',
      zh: '按约三至七块估算。USDA 的沥干油浸番茄每块重 3 克。',
    }),
    sandwichCapers,
  ], lunetteSandwich),
  'mortadella brie': estimate([
    sandwichBread,
    mortadella,
    brie,
    dijonnaise,
  ], lunetteSandwich),
  'butty croissantwich': estimate([
    butterCroissant,
    friedEgg,
    pickledRedOnion,
    cookedBacon,
    ingredient({en: 'Beef-fat fries (restaurant fries as reference)', zh: '牛油薯条（以餐厅薯条估算）'}, [30, 60], 169836, [289, 37.2, 14.04, 3.49], usdaFood(169836), {
      en: 'Assumes a small layer of fries inside the croissant, about one-sixth to one-third of a USDA restaurant serving.',
      zh: '按可颂里的一小层薯条估算，约为 USDA 餐厅标准份量的六分之一至三分之一。',
    }),
    ingredient({en: 'Gruyère', zh: '格鲁耶尔芝士'}, [20, 35], 171242, [413, 0.36, 32.34, 29.81], usdaFood(171242), {
      en: 'Assumes about one slice, allowing for slice thickness. USDA lists 28 g per slice.',
      zh: '按约一片估算，并计入厚薄差异。USDA 的芝士每片重 28 克。',
    }),
    arugula,
  ], {
    en: 'Includes the fries inside the croissant. Side greens, fries or soup are separate.',
    zh: '计入可颂里的薯条。配菜沙拉、薯条或汤另计。',
  }),
  'toast a lavocat': estimate([
    ingredient({en: 'Toasted baguette', zh: '烤法棍'}, [45, 75], 174911, [319, 61.93, 2.14, 13], lunetteMenu, {
      en: 'The brunch menu specifies baguette. Assumes roughly one medium toasted portion, which USDA lists at 59 g.',
      zh: '早午餐菜单标明使用法棍。按约一片中等份量估算，USDA 的中片烤法式面包重 59 克。',
    }),
    ingredient({en: 'Avocado flesh', zh: '牛油果果肉'}, [70, 120], 171705, [160, 8.53, 14.66, 2], avocadoToastRecipe, {
      en: 'The reference toast uses half an avocado. USDA lists about 201 g of edible flesh for a whole avocado of unspecified variety.',
      zh: '参考食谱每份用半个牛油果。USDA 的通用记录中，整颗可食果肉约 201 克。',
    }),
    ingredient({en: 'Hemp hearts', zh: '去壳火麻仁'}, [5, 12], 170148, [553, 8.67, 48.75, 31.56], hempHearts, {
      en: 'Assumes a small sprinkle, about ½–1 tablespoon. Manitoba Harvest lists 30 g per three tablespoons.',
      zh: '按少量撒料约 ½–1 汤匙估算。Manitoba Harvest 的火麻仁每三汤匙重 30 克。',
    }),
    ingredient({en: 'Everything seasoning (sesame as reference)', zh: '综合香料（以芝麻估算）'}, [2, 5], 170151, [565, 25.74, 48, 16.96], lunetteMenu, {
      en: 'The menu lists everything spice. Assumes a light dusting and uses toasted sesame as a proxy for the seed mix.',
      zh: '菜单标明使用综合香料。按少量撒料估算，其中的种子混合物以烤芝麻代替。',
    }),
  ], {
    en: 'Estimated for the avocado toast itself. Side greens, fries or soup are separate.',
    zh: '仅估算牛油果吐司本身，配菜沙拉、薯条或汤另计。',
  }),
  'roast beef sandwich': estimate([
    butterCroissant,
    ingredient({en: 'Sliced roast beef (roasted top round as reference)', zh: '切片烤牛肉（以烤牛后腿肉估算）'}, [70, 115], 174007, [162, 0, 3.77, 30.09], roastBeefRecipe, {
      en: 'The reference sandwich uses about 3 oz (85 g) of sliced roast beef.',
      zh: '参考三明治每份用约 3 盎司（85 克）切片烤牛肉。',
    }),
    arugula,
    ingredient({en: 'Horseradish cream (horseradish sauce as reference)', zh: '辣根奶油（以辣根酱估算）'}, [10, 22], 171833, [503, 10.05, 50.89, 1.09], usdaFood(171833), {
      en: 'Assumes about two to four teaspoons of creamy spread. USDA lists 5.6 g per teaspoon of horseradish sauce.',
      zh: '按约两至四茶匙奶油酱估算。USDA 的辣根酱每茶匙重 5.6 克。',
    }),
  ], lunetteSandwich),
  'croissant blt': estimate([
    butterCroissant,
    cookedBacon,
    ingredient({en: 'Sliced tomato', zh: '番茄片'}, [35, 65], 170457, [18, 3.89, 0.2, 0.88], croissantBltRecipe, {
      en: 'The reference sandwich uses two or three slices. USDA lists 20 g per medium slice.',
      zh: '参考三明治用两至三片。USDA 的普通番茄片每片重 20 克。',
    }),
    arugula,
    dijonnaise,
  ], lunetteSandwich),
  'croque madame': estimate([
    sandwichBread,
    mortadella,
    brie,
    ingredient({en: 'Mornay (cheese sauce as reference)', zh: '莫尔奈酱（以芝士酱估算）'}, [25, 65], 171191, [174, 6.83, 13.29, 6.71], croqueMadameRecipe, {
      en: 'The reference spreads 1½ tablespoons of sauce per sandwich. The upper end allows for extra sauce over the top.',
      zh: '参考食谱每份抹 1½ 汤匙酱。上限计入可能额外淋在表面的酱。',
    }),
    friedEgg,
    ingredient({en: 'Butter for griddling the bread (assumed)', zh: '烤面包用黄油（按常见做法估算）'}, [4, 8], 173430, [717, 0.06, 81.11, 0.85], croqueMadameRecipe, {
      en: 'Assumes a thin brushing on the bread, about one to two teaspoons. The fried egg is counted separately.',
      zh: '按薄涂在面包上的约一至两茶匙估算。煎蛋另计。',
    }),
  ], lunetteSandwich),
};
