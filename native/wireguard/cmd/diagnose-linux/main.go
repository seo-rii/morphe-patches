//go:build linux && !android

// SPDX-License-Identifier: GPL-3.0-only

// diagnose-linux only reports the operation that prevents upstream's default
// Linux transport from starting. It does not request privileges, retry using a
// different socket, or modify policy. Android disables this route listener.
package main

import (
	"fmt"
	"os"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
)

func main() {
	bind := conn.NewDefaultBind()
	_, _, err := bind.Open(0)
	if err != nil {
		fmt.Fprintf(os.Stderr, "upstream UDP Bind.Open: %v\n", err)
		os.Exit(1)
	}
	_ = bind.Close()
	fmt.Println("upstream UDP Bind.Open: success")
	fd, err := unix.Socket(unix.AF_NETLINK, unix.SOCK_RAW|unix.SOCK_CLOEXEC, unix.NETLINK_ROUTE)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Linux route-monitor socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE): %v\n", err)
		os.Exit(1)
	}
	defer unix.Close(fd)
	if err := unix.Bind(fd, &unix.SockaddrNetlink{Family: unix.AF_NETLINK, Groups: unix.RTMGRP_IPV4_ROUTE}); err != nil {
		fmt.Fprintf(os.Stderr, "Linux route-monitor bind(RTMGRP_IPV4_ROUTE): %v\n", err)
		os.Exit(1)
	}
	fmt.Println("Linux route-monitor creation: success")
}
