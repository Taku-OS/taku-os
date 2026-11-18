const { withMainApplication } = require('@expo/config-plugins');

const FACTORY = 'ExpoReactHostFactory.getDefaultReactHost(';
const WRAPPER = 'org.agentos.agent.home.TakuReactHost';

function configureReactHost(contents) {
  if (contents.includes(`${WRAPPER}(`)) return contents;
  const start = contents.indexOf(FACTORY);
  if (start < 0 || contents.indexOf(FACTORY, start + FACTORY.length) >= 0) {
    throw new Error('Taku ReactHost: expected one Expo host factory; review the generated template.');
  }
  // Wrap the factory expression, preserving package registration and every factory argument.
  let depth = 1;
  let end = start + FACTORY.length;
  const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[()]/g;
  tokens.lastIndex = end;
  for (let token; (token = tokens.exec(contents)) && depth > 0;) {
    if (token[0] === '(') depth++;
    if (token[0] === ')') depth--;
    end = tokens.lastIndex;
  }
  if (depth !== 0) throw new Error('Taku ReactHost: unterminated host factory expression.');
  return `${contents.slice(0, start)}${WRAPPER}(${contents.slice(start, end)})${contents.slice(end)}`;
}

module.exports = function withTakuReactHost(config) {
  return withMainApplication(config, (result) => {
    if (result.modResults.language !== 'kt') throw new Error('Taku ReactHost requires the Kotlin Expo application template.');
    result.modResults.contents = configureReactHost(result.modResults.contents);
    return result;
  });
};

module.exports._test = { configureReactHost };
