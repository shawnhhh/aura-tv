const fs=require('fs'),path=require('path'),assert=require('assert');
const root=path.resolve(__dirname,'../app/src/main/assets/config');
assert(!fs.existsSync(path.join(root,'quick.html')));assert(!fs.existsSync(path.join(root,'quick.js')));
require('./phone_center_browser_test.cjs');
