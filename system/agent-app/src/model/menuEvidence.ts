import {chickenjoyNutrition, katzNutrition, lunetteNutrition, type MenuNutrition} from './menuNutrition.ts';
import {menuPriceCurrency, type MenuDish, type MenuDishEvidence, type MenuPickLabels, type MenuVenue} from './menuScan.ts';

type PublishedMenu = {
  venue: MenuVenue;
  identifyingDishes: string[];
  evidence: Record<string, MenuDishEvidence>;
  suggestions?: Record<string, MenuPickLabels>;
  nutrition?: Record<string, MenuNutrition>;
};

const lunetteMenuUrl = 'https://www.cafelunette.com/bistro-menu/';
const lunette: MenuVenue = {
  name: 'Café Lunette',
  address: '1741 Lower Water St #160, Halifax',
  officialUrl: 'https://www.cafelunette.com/contact/',
  currency: 'CAD',
  instagramHandle: 'cafelunette.hfx',
};

const katzMenuUrl = 'https://katzsdelicatessen.com/store-menu';
const jollibeeMenuUrl = 'https://jollibee.com.sg/our-menu';
const chickenjoyNames = ['chickenjoy', '1pc chickenjoy ala carte', '1pc chickenjoy', '2pc chickenjoy',
  '6pc chickenjoy bucket', '8pc chickenjoy bucket'];
const publishedMenus: PublishedMenu[] = [
  {
    venue: {
      name: 'Jollibee',
      address: 'Singapore',
      officialUrl: jollibeeMenuUrl,
      currency: 'SGD',
    },
    identifyingDishes: [...chickenjoyNames, 'jolly spaghetti', 'peach mango pie', 'palabok fiesta'],
    nutrition: Object.fromEntries(chickenjoyNames.map(name => [name, chickenjoyNutrition])),
    evidence: Object.fromEntries(chickenjoyNames.map(name => [name, {
      facts: {en: 'Jollibee’s signature fried chicken.', zh: '快乐蜂招牌炸鸡。'},
      menuUrl: jollibeeMenuUrl,
      videoDishAliases: ['Chickenjoy', 'Chicken Joy'],
    }])),
  },
  {
    venue: lunette,
    identifyingDishes: ['butty croissantwich', 'mortadella brie', 'smoked gouda chicken', 'salade de tomates', 'brasserie caesar'],
    nutrition: lunetteNutrition,
    suggestions: {
      'baked camembert': {picks: {en: 'Good for sharing', zh: '适合分享'}},
      'french onion soup': {picks: {en: 'Warm & savoury', zh: '暖胃鲜香'}},
      'salade de tomates': {
        picks: {en: 'Fresh & tangy', zh: '清新微酸'},
        lighter: {en: 'Tomato salad', zh: '番茄沙拉'},
      },
      'brasserie caesar': {lighter: {en: 'Caesar salad', zh: '凯撒沙拉'}},
    },
    evidence: {
      'baked camembert': {
        facts: {
          en: 'Half a wheel of soft baked Camembert, with tangy fruit chutney and griddled baguette for dipping.',
          zh: '半轮烤软的卡芒贝尔芝士，配酸甜的水果酱和可以蘸着吃的烤法棍。',
        },
        ingredients: {en: 'Camembert, fruit chutney, griddled baguette', zh: '卡芒贝尔芝士、水果酸甜酱、烤法棍'},
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Baked Camembert', 'Camembert rôti', 'Camembert au four'],
      },
      'brasserie caesar': {
        facts: {
          en: 'Kale gives this Caesar salad a heartier bite. Bacon adds saltiness and the croutons add crunch.',
          zh: '羽衣甘蓝吃起来更有嚼劲，培根带咸香，烤面包丁增加脆感。',
        },
        ingredients: {en: 'Kale, bacon lardons, croutons, Parmesan', zh: '羽衣甘蓝、培根丁、烤面包丁、帕玛森芝士'},
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Caesar salad', 'Salade César', 'Ensalada César'],
      },
      'chicken liver parfait': {
        facts: {
          en: 'A smooth chicken liver spread with sweet-tart fruit chutney and crisp crostini.',
          zh: '细滑的鸡肝酱，配酸甜的水果酱和酥脆的烤面包片。',
        },
        ingredients: {en: 'Chicken liver, fruit chutney, crostini', zh: '鸡肝、水果酸甜酱、烤面包片'},
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Chicken liver parfait', 'Parfait de foie de volaille'],
      },
      'tuna melt': {
        facts: {
          en: 'A hot tuna sandwich with melted provolone. Olives and capers add a briny bite, and pickled onions and cornichons bring tang.',
          zh: '热烤金枪鱼三明治里有融化的普罗沃洛内芝士。橄榄和酸豆带咸鲜味，腌红洋葱和小酸黄瓜带酸味。',
        },
        ingredients: {
          en: 'Tuna, provolone, pickled red onion, olives, capers, cornichons',
          zh: '金枪鱼、普罗沃洛内芝士、腌红洋葱、橄榄、酸豆、小酸黄瓜',
        },
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Tuna melt', 'Tuna melt sandwich'],
      },
      'smoked gouda chicken': {
        facts: {
          en: 'The Gouda gives this chicken sandwich its smoky flavor. Sun-dried tomatoes add a concentrated tomato flavor, and capers bring a briny bite.',
          zh: '鸡肉三明治的烟熏香来自高达芝士。晒干番茄带来浓郁的番茄味，酸豆添一点咸鲜。',
        },
        ingredients: {en: 'Chicken, smoked Gouda, sun-dried tomatoes, capers', zh: '鸡肉、烟熏高达芝士、晒干番茄、酸豆'},
        menuUrl: lunetteMenuUrl,
      },
      'mortadella brie': {
        facts: {
          en: 'Thinly sliced mortadella and creamy Brie, with a little mustard tang from the dijonnaise.',
          zh: '薄切莫塔德拉香肠配绵软的布里芝士，第戎芥末蛋黄酱带一点酸香。',
        },
        ingredients: {en: 'Shaved mortadella, Brie, dijonnaise', zh: '薄切莫塔德拉香肠、布里芝士、第戎芥末蛋黄酱'},
        menuUrl: lunetteMenuUrl,
      },
      'butty croissantwich': {
        facts: {
          en: 'A hearty croissant sandwich with bacon, egg and fries inside. Pickled red onion adds a tangy bite.',
          zh: '可颂里夹了培根、鸡蛋和薯条，吃起来很扎实，腌红洋葱带一点酸味。',
        },
        ingredients: {
          en: 'Croissant, egg, pickled red onion, bacon, beef-fat fries, Gruyère, arugula',
          zh: '可颂、鸡蛋、腌红洋葱、培根、牛油炸薯条、格鲁耶尔芝士、芝麻菜',
        },
        menuUrl: lunetteMenuUrl,
      },
      'croissant blt': {
        facts: {
          en: 'Flaky croissant with salty bacon, juicy tomato and peppery arugula. Dijonnaise adds a little mustard tang.',
          zh: '酥松的可颂夹着咸香的培根和多汁的番茄，芝麻菜和第戎芥末蛋黄酱带一点辛香。',
        },
        ingredients: {en: 'Croissant, bacon, tomato, arugula, dijonnaise', zh: '可颂、培根、番茄、芝麻菜、第戎芥末蛋黄酱'},
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Croissant BLT', 'BLT on a croissant', 'BLT croissant', 'BLT egg croissant'],
      },
      'croque madame': {
        facts: {
          en: 'A rich, cheesy sandwich with mortadella, creamy Mornay sauce, Brie and a fried egg.',
          zh: '莫塔德拉香肠和煎蛋配上布里芝士和莫尔奈芝士酱，口味浓郁。',
        },
        ingredients: {en: 'Mortadella, Mornay cheese sauce, Brie, fried egg', zh: '莫塔德拉香肠、莫尔奈芝士酱、布里芝士、煎蛋'},
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Croque madame', 'Croque-madame'],
      },
      'roast beef sandwich': {
        facts: {
          en: 'Roast beef in a flaky croissant, with a peppery kick from arugula and horseradish cream.',
          zh: '酥松的可颂夹烤牛肉，芝麻菜和辣根奶油酱带一点辛辣。',
        },
        ingredients: {en: 'Croissant, roast beef, arugula, horseradish cream', zh: '可颂、烤牛肉、芝麻菜、辣根奶油酱'},
        menuUrl: lunetteMenuUrl,
      },
      'toast a lavocat': {
        facts: {en: 'Creamy avocado on a baguette, with the crunch of hemp hearts and everything spice.', zh: '绵滑的牛油果铺在法棍上，火麻仁和综合香料增添脆感。'},
        ingredients: {en: 'Baguette, avocado, hemp hearts, everything spice', zh: '法棍、牛油果、火麻仁、综合香料'},
        menuUrl: lunetteMenuUrl,
        videoDishAliases: ['Avocado toast', 'Avocado on toast', 'Toast à l’avocat'],
      },
      'salade de tomates': {
        facts: {
          en: 'Peach brings sweetness, while capers, basil vinaigrette and crisp prosciutto bring a tangy, salty contrast.',
          zh: '桃子带来甜味，酸豆、罗勒油醋汁和脆火腿让口味带一点酸和咸。',
        },
        menuUrl: lunetteMenuUrl,
        ingredients: {
          en: 'Tomatoes, capers, peach, Parmesan, basil vinaigrette, crispy prosciutto',
          zh: '番茄、酸豆、桃子、帕玛森芝士、罗勒油醋汁、脆火腿',
        },
        tasteTags: [{en: 'Peach & basil', zh: '桃子和罗勒'}, {en: 'Crisp prosciutto', zh: '脆火腿'}],
        videoDishAliases: ['Tomato salad', 'Heirloom tomato salad'],
      },
      'french onion soup': {
        facts: {
          en: 'A beef broth with Gruyère and toasted croutons. The broth makes this unsuitable for vegetarians.',
          zh: '牛肉汤底，配格鲁耶尔芝士和烤面包丁，不适合素食。',
        },
        ingredients: {en: 'Onion, Gruyère, croutons, beef broth', zh: '洋葱、格鲁耶尔芝士、烤面包丁、牛肉汤底'},
        menuUrl: lunetteMenuUrl,
        tasteTags: [{en: 'Gruyère', zh: '格鲁耶尔芝士'}, {en: 'Toasted croutons', zh: '烤面包丁'}],
        videoDishAliases: ['French onion soup', 'Soupe à l’oignon gratinée', 'Soupe à l’oignon'],
        socialPostUrls: [
          'https://www.tiktok.com/@printet.andan/video/7555254499011677458',
          'https://www.instagram.com/p/DMThijLpYTZ/',
          'https://www.instagram.com/p/CtjOt1oMDwF/',
        ],
        videoSourcePages: ['https://airial.travel/restaurants/canada/halifax/caf%C3%A9-lunette-QyE4Y7Qp'],
      },
    },
  },
  {
    venue: {
      name: 'Superba Food + Bread · Venice',
      address: '1900 S Lincoln Blvd, Venice, CA',
      officialUrl: 'https://www.lifesuperba.com/location/superba-venice/',
      currency: 'USD',
      pickupUrl: 'https://direct.chownow.com/order/1465/locations/1895',
      pickupDishNames: ['chicken chopped salad'],
    },
    identifyingDishes: ['chicken chopped salad', 'greens goddess salad', 'superba grain bowl', 'shrimp and avocado salad', 'umami salmon bowl'],
    evidence: {
      'chicken chopped salad': {
        facts: {
          en: 'Chicken, chickpeas and fresh mozzarella make this a more substantial salad. The za’atar vinaigrette adds herbs and spice.',
          zh: '鸡肉、鹰嘴豆和新鲜马苏里拉芝士，让这份沙拉更有饱腹感。调味用的是带香草和香料的扎塔尔油醋汁。',
        },
        menuUrl: 'https://www.lifesuperba.com/venice-menu/',
      },
    },
  },
  {
    venue: {
      name: "Katz's Delicatessen",
      address: '205 E Houston St, New York, NY',
      officialUrl: katzMenuUrl,
      currency: 'USD',
      instagramHandle: 'katzsdeli',
    },
    identifyingDishes: ['katzs pastrami', 'katzs corned beef', 'katzs brisket', 'katzs knoblewurst'],
    nutrition: katzNutrition,
    suggestions: {
      'katzs pastrami': {
        picks: {en: 'Katz’s signature', zh: '店里招牌'},
        lighter: {en: 'Ask for lean pastrami', zh: '可选瘦切烟熏牛肉'},
      },
      'katzs corned beef': {
        picks: {en: 'Deli classic', zh: '熟食店经典'},
        lighter: {en: 'Ask for lean corned beef', zh: '可选瘦切腌牛肉'},
      },
      'katzs brisket': {
        picks: {en: 'Slow-roasted', zh: '慢烤软嫩'},
        lighter: {en: 'Try half with salad', zh: '可点半份配沙拉'},
      },
    },
    evidence: {
      'katzs pastrami': {
        facts: {
          en: 'Katz’s cures beef navel, coats it in spices and slow-smokes it. The warm pastrami is hand-carved to order.',
          zh: 'Katz’s 把牛腹肉腌好后抹上香料，再慢慢烟熏。点单后，店员会把热乎的烟熏牛肉手切装进面包。',
        },
        ingredients: {en: 'House-smoked beef pastrami, deli rye bread, optional mustard', zh: '自家烟熏牛腹肉、黑麦面包、可选芥末酱'},
        menuUrl: katzMenuUrl,
        tasteTags: [{en: 'Smoky', zh: '烟熏香'}, {en: 'Spice crust', zh: '香料外层'}],
        videoDishAliases: ["Katz's Pastrami Sandwich", 'Pastrami Sandwich', 'Pastrami on Rye'],
        socialPostUrls: [
          'https://www.tiktok.com/@jacksdiningroom/video/7343375235887942954',
          'https://www.youtube.com/shorts/TpOKqIRs_uo',
        ],
      },
      'katzs corned beef': {
        facts: {
          en: 'Katz’s cures beef brisket for about a month, then boils and steams it until tender. The meat is carved by hand.',
          zh: '牛胸肉先腌约一个月，再水煮、蒸至软嫩，最后由店员手切。',
        },
        ingredients: {en: 'Cured beef brisket, deli rye bread, optional mustard', zh: '腌牛胸肉、黑麦面包、可选芥末酱'},
        menuUrl: katzMenuUrl,
        tasteTags: [{en: 'Savoury', zh: '咸香'}, {en: 'Tender slices', zh: '肉片软嫩'}],
        videoDishAliases: ["Katz's Corned Beef Sandwich", 'Corned Beef Sandwich', 'Corned Beef on Rye'],
        socialPostUrls: [
          'https://www.facebook.com/reel/1142161518501775/',
          'https://www.youtube.com/watch?v=T0h_tDyJQaA',
          'https://www.youtube.com/watch?v=lrpg54qNt-U',
        ],
      },
      'katzs brisket': {
        facts: {
          en: 'Katz’s slow-roasts this beef brisket for hours in its house gravy. The meat is juicy and tender.',
          zh: '牛胸肉在店家自制的肉汁里慢烤数小时，吃起来软嫩多汁。',
        },
        ingredients: {en: 'Beef brisket, house gravy, deli rye bread', zh: '牛胸肉、自制肉汁、黑麦面包'},
        menuUrl: katzMenuUrl,
        tasteTags: [{en: 'Rich gravy', zh: '肉汁浓郁'}, {en: 'Juicy brisket', zh: '牛胸肉多汁'}],
        videoDishAliases: ["Katz's Brisket Sandwich", 'Brisket Sandwich', 'Brisket on Rye'],
        socialPostUrls: [
          'https://www.youtube.com/shorts/5rTmHahwZBk',
          'https://www.youtube.com/watch?v=DYqkmoAhNeE',
        ],
      },
    },
  },
];

export const menuDishKey = (name: string) => name.normalize('NFKD').toLowerCase()
  .replace(/[\p{M}'’]/gu, '').replace(/[^a-z0-9]+/g, ' ').trim();

function oneMenuCharacterDiffers(left: string, right: string): boolean {
  if (left.length > right.length) return oneMenuCharacterDiffers(right, left);
  if (right.length - left.length > 1) return false;
  const mismatch = [...left].findIndex((character, index) => character !== right[index]);
  if (mismatch < 0) return right.length > left.length;
  return left.slice(mismatch + Number(left.length === right.length)) === right.slice(mismatch + 1);
}

function matchedDishKey(key: string, published: Set<string>): string {
  if (published.has(key) || key.length < 8) return key;
  const compact = key.replaceAll(' ', '');
  const candidates = [...published].filter(name => oneMenuCharacterDiffers(compact, name.replaceAll(' ', '')));
  return candidates.length === 1 ? candidates[0] : key;
}

function withBrowsePicks(dishes: MenuDish[]): MenuDish[] {
  if (dishes.length === 0 || dishes.some(dish => dish.suggestions?.picks)) return dishes;

  const distinct = [...new Map(dishes.map(dish => [menuDishKey(dish.name), dish.id])).values()];
  const count = Math.min(distinct.length, 5);
  const picks = new Set(Array.from({length: count}, (_, index) =>
    distinct[Math.round(index * (distinct.length - 1) / Math.max(1, count - 1))]));
  return dishes.map(dish => picks.has(dish.id)
    ? {...dish, suggestions: {...dish.suggestions, picks: {en: 'Explore', zh: '看看这道菜'}}}
    : dish);
}

export function matchPublishedMenu(dishes: MenuDish[]): {venue?: MenuVenue; dishes: MenuDish[]} {
  const names = new Set(dishes.map(dish => menuDishKey(dish.name)));
  const explicitCurrencies = new Set(dishes.flatMap(({price}) => menuPriceCurrency(price) ?? []));
  const matches = publishedMenus.filter(menu => menu.identifyingDishes.filter(name => names.has(name)).length >= 2
    && (!explicitCurrencies.size || (explicitCurrencies.size === 1 && menu.venue.currency && explicitCurrencies.has(menu.venue.currency))));
  if (matches.length !== 1) return {dishes: withBrowsePicks(dishes)};
  const [match] = matches;
  const published = new Set([...Object.keys(match.nutrition ?? {}), ...Object.keys(match.evidence),
    ...Object.keys(match.suggestions ?? {})]);
  return {
    venue: match.venue,
    dishes: withBrowsePicks(dishes.map(dish => {
      const key = matchedDishKey(menuDishKey(dish.name), published);
      const evidence = match.evidence[key];
      const suggestions = match.suggestions?.[key];
      const nutrition = match.nutrition?.[key];
      const currency = menuPriceCurrency(dish.price) ?? match.venue.currency;
      return {...dish, currency, evidence, suggestions, nutrition};
    })),
  };
}
