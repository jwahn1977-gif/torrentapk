import {createContext, useContext} from 'react';

export type ThemeMode = 'system' | 'light' | 'dark';

export const DARK = {
  dark: true,
  bg: '#0f1412',
  card: '#18201c',
  text: '#e8efe9',
  sub: '#95a39a',
  line: '#26302b',
  accent: '#7cc35b',
  accentText: '#0f1412',
  track: '#2a352f',
  danger: '#ff7a6e',
  warn: '#e7b450',
  input: '#121916',
};

export const LIGHT: typeof DARK = {
  dark: false,
  bg: '#f4f6f3',
  card: '#ffffff',
  text: '#16201a',
  sub: '#5f6d64',
  line: '#e2e7e2',
  accent: '#4f9a2f',
  accentText: '#ffffff',
  track: '#e6ece5',
  danger: '#c8382c',
  warn: '#a86f00',
  input: '#f8faf7',
};

export type Palette = typeof DARK;

export const PaletteContext = createContext<Palette>(DARK);
export const usePalette = () => useContext(PaletteContext);

export function fmtSize(b: number): string {
  if (!b || b <= 0) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(Math.floor(Math.log(b) / Math.log(1024)), u.length - 1);
  return `${(b / Math.pow(1024, i)).toFixed(i >= 2 ? 2 : 0)} ${u[i]}`;
}
export const fmtRate = (b: number) => `${fmtSize(b)}/s`;
