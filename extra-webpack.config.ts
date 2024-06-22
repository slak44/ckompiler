/* eslint-disable */
import { Configuration } from 'webpack';
import * as MonacoWebpackPlugin from 'monaco-editor-webpack-plugin';

const config: Configuration = {
  module: {
    rules: [
      {
        test: /node_modules\/.*?\.css$/,
        use: ['style-loader', 'css-loader'],
      },
      {
        test: /\.ttf$/,
        type: 'asset/resource',
      },
    ],
  },
  plugins: [new MonacoWebpackPlugin()],
};

export default config;
