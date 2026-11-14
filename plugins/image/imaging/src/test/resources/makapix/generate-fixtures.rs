// Compile against upstream makapix-engine at e2d427f881dd561aae9d6091af7d54ca4bb264a6.
// rustc --edition=2021 --extern makapix_engine=libmakapix_engine.rlib generate-fixtures.rs
use makapix_engine::{Document, Rgba8};
use makapix_engine::document::{Layer, Frame, Palette, LoopMode};
use makapix_engine::document::BlendMode;
use makapix_engine::selection::Mask;
use std::sync::Arc;
fn main() {
    let dir = std::env::args().nth(1).unwrap();
    let mut doc = Document::new(64,64);
    let base = &mut doc.frames[0].layers[0];
    base.name = "Native tiles".into();
    // Materialize this tile before alpha-zero writes (set on absent transparent tile is a no-op).
    base.pixels.set(96,96,Rgba8::new(0,0,0,255));
    for y in 0..64 { for x in 0..64 {
        let c = match (x/32,y/32) {
            (0,0) => if y < 16 {Rgba8::new(255,0,0,255)} else {Rgba8::new(0,0,255,255)}, // RLE
            (1,0) => if (x+y)%2==0 {Rgba8::new(0,255,0,255)} else {Rgba8::new(255,255,0,255)}, // INDEXED
            (0,1) => Rgba8::new(x as u8, y as u8, (x*y) as u8, (x+y) as u8), // RAW
            _ => Rgba8::new(31,63,95,0), // preserve RGB beneath alpha zero
        };
        base.pixels.set(x+64,y+64,c);
    }}
    let mut overlay = Layer::new(3,doc.storage(),"Multiply");
    overlay.blend = BlendMode::Multiply;
    overlay.opacity = 123;
    overlay.locked = true;
    overlay.pixels.set(65,66,Rgba8::new(10,20,30,40));
    doc.frames[0].layers.push(overlay);
    doc.frames[0].duration_us=16667;
    doc.frames[0].active_layer=1;
    doc.frames.push(Frame {id:7,duration_us:250001,layers:doc.frames[0].layers.clone(),active_layer:0});
    doc.active_frame=1;
    doc.anim.loop_mode=LoopMode::PingPong;
    let mut palette=Palette::new("Named",vec![Rgba8::new(1,2,3,4),Rgba8::new(255,255,255,255)]);
    palette.color_names[0]=Some("Cloud".into());
    doc.palettes=vec![palette];
    let mut selection=Mask::new(192,192); selection.set(65,66,true); selection.set(68,70,true);
    doc.selection=Some(Arc::new(selection));
    let bytes=makapix_engine::io::save_to_bytes(&doc);
    makapix_engine::io::load_from_bytes(&bytes).unwrap();
    std::fs::write(format!("{dir}/native-all-codecs.mkpx"),bytes).unwrap();
    doc.frames[1].layers[0].pixels.set(2,3,Rgba8::new(22,33,44,55));
    std::fs::write(format!("{dir}/native-gutter.mkpx"),makapix_engine::io::save_to_bytes(&doc)).unwrap();
}
