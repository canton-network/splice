import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import eslintConfigPrettier from "eslint-config-prettier/flat";
import tanstackQuery from "@tanstack/eslint-plugin-query";
import { defineConfig } from "eslint/config";

export default defineConfig([
  {
    files: ["**/*.{js,mjs,cjs,ts,jsx,tsx}"],
    plugins: { js },
    extends: ["js/recommended"],
    rules: {
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/explicit-module-boundary-types": "warn",
    },
  },
  {
    files: ["**/*.{js,mjs,cjs,ts,jsx,tsx}"],
    languageOptions: { globals: globals.browser },
  },
  tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,
  tanstackQuery.configs["flat/recommended"],
  eslintConfigPrettier,
  {
    files: ["**/*.{js,mjs,cjs,ts,jsx,tsx}"],
    rules: {
      // TODO(#8138): fix the sites and re-enable, one rule at a time.
      "react-hooks/refs": "off",
      "react-hooks/set-state-in-effect": "off",
      "react-hooks/immutability": "off",
      "@typescript-eslint/no-non-null-asserted-optional-chain": "off",
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          args: "all",
          argsIgnorePattern: "^_",
          caughtErrors: "all",
          caughtErrorsIgnorePattern: "^_",
          destructuredArrayIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          ignoreRestSiblings: true,
        },
      ],
      "no-restricted-properties": [
        "error",
        {
          property: "sort",
          message:
            "Array.prototype.sort() sorts in place. React-query caches query results and hands the same array to every consumer, so sorting one in place silently mutates the cached data for all other components and hooks. Use toSorted() instead, which returns a new array.",
        },
      ],
    },
  },
  {
    files: ["vite.config.mjs"],
    languageOptions: { globals: globals.node },
  },
]);
