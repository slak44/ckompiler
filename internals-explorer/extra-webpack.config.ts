/* eslint-disable */
import { Configuration } from 'webpack';
const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');

export default {
  module: {
    rules: [
      {
        test: /\.ttf$/,
        use: ['file-loader']
      }
    ]
  },
  plugins: [new MonacoWebpackPlugin()]
} as Configuration;
