#[path="/workspace/old-sources/makapix-reference/crates/codec/src/mkpx_compact.rs"]
mod compact;
fn main() {
 let args: Vec<String> = std::env::args().collect();
 if args[1] == "compress" {
  let plain=std::fs::read(&args[2]).unwrap();
  let bytes=compact::compress(&plain);
  assert_eq!(compact::open(&bytes).unwrap(),plain);
  std::fs::write(&args[3],bytes).unwrap();
 } else {
  let input=compact::open(&std::fs::read(&args[2]).unwrap()).unwrap();
  let original=makapix_engine::io::load_from_bytes(&input).unwrap();
  let export=compact::open(&std::fs::read(&args[3]).unwrap()).unwrap();
  let copy=makapix_engine::io::load_from_bytes(&export).unwrap();
  assert_eq!(original.content_hash(),copy.content_hash());
  assert_eq!(original.frames.len(),copy.frames.len());
  assert_eq!(original.palettes[0].color_names,copy.palettes[0].color_names);
  assert_eq!(original.frames[0].active_layer,copy.frames[0].active_layer);
  assert_eq!(original.active_frame,copy.active_frame);
  println!("strict upstream load and content hash match: {}",args[3]);
 }
}
