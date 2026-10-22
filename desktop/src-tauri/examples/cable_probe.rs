//! Lists USB devices as the cable link sees them (through UsbDk), without opening any of them.
//! `cargo run --example cable_probe` — with `--switch`, also asks the phones to enter accessory mode.
use rusb::UsbContext;

fn main() {
    let ctx = match rusb::Context::with_options(&[rusb::UsbOption::use_usbdk()]) {
        Ok(c) => c,
        Err(e) => return println!("libusb with UsbDk failed: {e}"),
    };
    for dev in ctx.devices().expect("device list").iter() {
        let Ok(d) = dev.device_descriptor() else { continue };
        let ifaces: Vec<String> = dev
            .active_config_descriptor()
            .or_else(|_| dev.config_descriptor(0))
            .map(|c| {
                c.interfaces()
                    .flat_map(|i| i.descriptors().map(|a| format!("{:02x}/{:02x}/{:02x}", a.class_code(), a.sub_class_code(), a.protocol_code())).collect::<Vec<_>>())
                    .collect()
            })
            .unwrap_or_default();
        println!("bus {} addr {:>3}  {:04x}:{:04x}  interfaces {}", dev.bus_number(), dev.address(), d.vendor_id(), d.product_id(), ifaces.join(" "));
    }
    if std::env::args().any(|a| a == "--switch") {
        let cable = switchflow_lib::cable::Cable::new();
        println!("switched: {}", cable.switch_phones());
    }
}
