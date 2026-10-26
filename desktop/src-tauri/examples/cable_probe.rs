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
        println!("bus {} addr {:>3}  {:04x}:{:04x}  {:?}  usb {}  interfaces {}", dev.bus_number(), dev.address(), d.vendor_id(), d.product_id(), dev.speed(), d.usb_version(), ifaces.join(" "));
    }
    if std::env::args().any(|a| a == "--iphones") {
        println!("usbmuxd: available={}, devices={:?}", switchflow_lib::usbmux::available(), switchflow_lib::usbmux::list_devices());
        match switchflow_lib::usbmux::connect(1, switchflow_lib::usbmux::DEVICE_PORT) {
            Ok(_) => println!("connect to device 1: opened"),
            Err(e) => println!("connect to device 1: {e}"),
        }
    }
    #[cfg(windows)]
    if std::env::args().any(|a| a == "--mtp") {
        println!("MTP interfaces without a driver: {:?}", switchflow_lib::discovery::setupapi::mtp_without_driver());
        for d in switchflow_lib::discovery::wpd::Watcher::new().poll() {
            println!("WPD: {} {:?} {} {} {}", d.id, d.platform, d.manufacturer, d.model, d.status);
        }
    }
    #[cfg(windows)]
    if std::env::args().any(|a| a == "--usbflags") {
        for k in ["18D12D000504", "18D12D010504", "18D12D000400", "18D12D010400", "18D12D009999"] {
            println!("{k}: needs fix = {}", switchflow_lib::cable::usbflags::needs_fix(k));
        }
    }
    if std::env::args().any(|a| a == "--hide") {
        let cable = switchflow_lib::cable::Cable::new();
        println!("UsbDk available: {}, accessory phones hidden from Windows: {}", cable.available(), cable.hiding());
    }
    if std::env::args().any(|a| a == "--switch") {
        let cable = switchflow_lib::cable::Cable::new();
        println!("switched: {}", cable.switch_phones());
    }
    // `--switch-id 04e8:686c`: the same accessory handshake for one device, whatever its USB mode.
    let args: Vec<String> = std::env::args().collect();
    if let Some(id) = args.iter().position(|a| a == "--switch-id").and_then(|i| args.get(i + 1)) {
        let (vid, pid) = id.split_once(':').map(|(v, p)| (u16::from_str_radix(v, 16).unwrap(), u16::from_str_radix(p, 16).unwrap())).unwrap();
        let t = std::time::Duration::from_secs(2);
        for dev in ctx.devices().expect("device list").iter() {
            let Ok(d) = dev.device_descriptor() else { continue };
            if (d.vendor_id(), d.product_id()) != (vid, pid) {
                continue;
            }
            let h = dev.open().expect("open");
            let mut ver = [0u8; 2];
            h.read_control(0xC0, 51, 0, 0, &mut ver, t).expect("get protocol");
            for (i, s) in ["SwitchFlow", "SwitchFlow Cable", "SwitchFlow: перенос данных по кабелю", "1"].iter().enumerate() {
                let mut b = s.as_bytes().to_vec();
                b.push(0);
                h.write_control(0x40, 52, 0, i as u16, &b, t).expect("send string");
            }
            h.write_control(0x40, 53, 0, 0, &[], t).expect("start");
            println!("switched {id}, AOA protocol {}", u16::from_le_bytes(ver));
            // Close at once: holding the handle until the phone leaves hangs UsbDk (see cable.rs).
            drop(h);
            // `--watch`: print the phone-maker devices every 500 ms.
            if args.iter().any(|a| a == "--watch") {
                for i in 0..16 {
                    std::thread::sleep(std::time::Duration::from_millis(500));
                    let seen: Vec<String> = ctx
                        .devices()
                        .map(|l| {
                            l.iter()
                                .filter_map(|d| d.device_descriptor().ok())
                                .filter(|d| switchflow_lib::cable::is_phone_vendor(d.vendor_id()))
                                .map(|d| format!("{:04x}:{:04x}", d.vendor_id(), d.product_id()))
                                .collect()
                        })
                        .unwrap_or_else(|e| vec![format!("list failed: {e}")]);
                    println!("{} ms: {:?}", (i + 1) * 500, seen);
                }
            }
        }
    }
}
