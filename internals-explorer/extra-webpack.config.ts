/* eslint-disable */
import { Configuration } from 'webpack';
import * as MonacoWebpackPlugin from 'monaco-editor-webpack-plugin';

const config: Configuration = {
  module: {
    rules: [
      {
        test: /node_modules\/.*?\.css$/,
        use: [
          'style-loader',
          {
            loader: 'css-loader',
            options: {
              url: false,
            },
          },
        ],
      },
      {
        test: /\.ttf$/,
        use: ['file-loader'],
      },
    ],
  },
  plugins: [new MonacoWebpackPlugin()],
};

export default config;
