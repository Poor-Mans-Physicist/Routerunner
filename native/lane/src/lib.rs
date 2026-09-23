//! A 1:1 Rust port of the Routerunner lane planner (`com.routerunner.lane`), as a JNI library
//! and as the engine behind the `lane_cli` binary.
//!
//! Semantics follow the Java source exactly, including the parts that are only incidentally
//! defined: `java.util.HashMap` iteration order where the planner iterates a map, Java's
//! `PriorityQueue` heap order where A* ties, `Math.round`, and `Double.toString` in the JSON.

pub mod chain;
pub mod export;
pub mod grid;
pub mod jcompat;
pub mod jni;
pub mod model;
pub mod planner;
