version 1.0

task foo {
    command <<<
        cat <<-EOF > test.py
a = 1
if a == 0:
    print("a = 0")
else:
    print("a = 1")
EOF
        python3 test.py
    >>>
}
