version 1.1

struct MyStructDB {
  Int? num_columns
  Int num_rows
}

task test_task {
  input {
    MyStructDB database
  }

  command <<< 
    echo ~{database.num_columns}
    echo ~{database.num_rows}
  >>>

  runtime {}

  output {}
}
