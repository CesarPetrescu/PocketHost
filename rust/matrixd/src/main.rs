use std::env;
use std::fs;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

fn main() {
    let mut addr = String::from("127.0.0.1:6167");
    let mut data_dir = PathBuf::from("./data/matrix");

    let args: Vec<String> = env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--addr" if i + 1 < args.len() => {
                addr = args[i + 1].clone();
                i += 2;
            }
            "--data-dir" if i + 1 < args.len() => {
                data_dir = PathBuf::from(&args[i + 1]);
                i += 2;
            }
            _ => i += 1,
        }
    }

    if let Err(err) = fs::create_dir_all(&data_dir) {
        eprintln!("MATRIXD failed to create data dir: {err}");
        std::process::exit(1);
    }

    let started = Instant::now();
    let listener = TcpListener::bind(&addr).unwrap_or_else(|err| {
        eprintln!("MATRIXD failed to bind {addr}: {err}");
        std::process::exit(1);
    });
    println!("MATRIXD placeholder listening addr={addr} data_dir={}", data_dir.display());

    for stream in listener.incoming() {
        match stream {
            Ok(mut s) => handle(&mut s, started, &addr, &data_dir),
            Err(err) => eprintln!("MATRIXD accept error: {err}"),
        }
    }
}

fn handle(stream: &mut TcpStream, started: Instant, addr: &str, data_dir: &PathBuf) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let mut buf = [0u8; 2048];
    let n = match stream.read(&mut buf) {
        Ok(n) => n,
        Err(err) => {
            eprintln!("MATRIXD read error: {err}");
            return;
        }
    };
    let req = String::from_utf8_lossy(&buf[..n]);
    let first = req.lines().next().unwrap_or("");
    let path = first.split_whitespace().nth(1).unwrap_or("/");

    if path == "/health" {
        let body = format!(
            "{{\"service\":\"matrixd-placeholder\",\"status\":\"ok\",\"addr\":\"{}\",\"uptime_seconds\":{},\"data_dir\":\"{}\",\"time_unix\":{}}}\n",
            escape(addr),
            started.elapsed().as_secs(),
            escape(&data_dir.display().to_string()),
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
        );
        respond(stream, 200, "application/json", &body);
    } else if path == "/_matrix/client/versions" {
        let body = "{\"versions\":[\"r0.6.1\"],\"unstable_features\":{\"dev.pockethost.placeholder\":true}}\n";
        respond(stream, 200, "application/json", body);
    } else {
        let body = "Matrix placeholder only. Replace libmatrixd.so with a real Conduit/Tuwunel/Dendrite-compatible Android build.\n";
        respond(stream, 501, "text/plain; charset=utf-8", body);
    }
}

fn respond(stream: &mut TcpStream, status: u16, content_type: &str, body: &str) {
    let reason = match status {
        200 => "OK",
        501 => "Not Implemented",
        _ => "Status",
    };
    let response = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        reason,
        content_type,
        body.as_bytes().len(),
        body
    );
    let _ = stream.write_all(response.as_bytes());
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}
